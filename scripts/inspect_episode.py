#!/usr/bin/env python3
"""Validate an exported V0.2.3 original-MJPEG/IMU Episode on Linux."""

import argparse
import csv
import json
from pathlib import Path


def read_rows(path):
    with path.open(newline="", encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


def inspect(root, decode=False):
    metadata = json.loads((root / "metadata.json").read_text(encoding="utf-8"))
    quality = json.loads((root / "quality.json").read_text(encoding="utf-8"))
    video = (root / "video.mjpg").read_bytes()
    frames = read_rows(root / "video_index.csv")
    imu = (root / "imu_packets.bin").read_bytes()
    packets = read_rows(root / "imu_packets.csv")
    samples = read_rows(root / "imu_samples.csv")
    errors = []
    if metadata.get("video_format") != "ORIGINAL_MJPEG":
        errors.append("metadata does not declare ORIGINAL_MJPEG")
    if len(frames) != quality["video_frames_saved"]:
        errors.append("video index row count differs from quality")
    if len(video) != quality["video_bytes_saved"]:
        errors.append("video byte count differs from quality")
    if len(packets) != quality["imu_packets"] or len(imu) != len(packets) * 252:
        errors.append("IMU packet count/bytes mismatch")
    if len(samples) != quality["imu_samples"] or len(samples) != len(packets) * 14:
        errors.append("IMU sample count mismatch")

    decoder = None
    if decode:
        from io import BytesIO
        from PIL import Image
        decoder = (BytesIO, Image)
    offset = 0
    previous_index = 0
    previous_time = 0
    for row in frames:
        index = int(row["frame_index"])
        at = int(row["byte_offset"])
        size = int(row["jpeg_bytes"])
        boot_ns = int(row["frame_receive_boottime_ns"])
        if index <= previous_index or at != offset or size <= 0 or boot_ns <= previous_time:
            errors.append(f"frame {index}: index/offset/time order invalid")
        jpeg = video[at:at + size]
        if len(jpeg) != size or jpeg[:2] != b"\xff\xd8" or jpeg[-2:] != b"\xff\xd9":
            errors.append(f"frame {index}: JPEG bytes or markers invalid")
        elif decoder:
            with decoder[1].open(decoder[0](jpeg)) as image:
                image.load()
                if image.size != (metadata["requested_width"], metadata["requested_height"]):
                    errors.append(f"frame {index}: decoded size {image.size} differs from request")
        offset += size
        previous_index, previous_time = index, boot_ns
    if offset != len(video):
        errors.append("video index does not cover entire video.mjpg")

    previous_last = None
    previous_query = 0
    missing_samples = 0
    for i, row in enumerate(packets):
        at = int(row["raw_offset_bytes"])
        before = int(row["host_before_boottime_ns"])
        after = int(row["host_after_boottime_ns"])
        if (int(row["packet_index"]) != i + 1 or at != i * 252 or
                int(row["raw_bytes"]) != 252 or before <= previous_query or after < before):
            errors.append(f"IMU packet {i + 1}: index/offset/time invalid")
        previous_query = before
        raw = imu[at:at + 252]
        if len(raw) != 252:
            continue
        counters = []
        for j in range(14):
            part = raw[j * 18:(j + 1) * 18]
            if part[:2] != b"\xde\xc8":
                errors.append(f"IMU packet {i + 1} sample {j}: invalid header")
            counter = int.from_bytes(part[2:6], "big")
            counters.append(counter)
            axes = [int.from_bytes(part[k:k + 2], "big", signed=True) for k in range(6, 18, 2)]
            if j and (counter - counters[j - 1]) & 0xffffffff != 32:
                errors.append(f"IMU packet {i + 1} sample {j}: counter discontinuity")
            pos = i * 14 + j
            if pos < len(samples):
                saved = samples[pos]
                saved_axes = [int(saved[name]) for name in
                              ("ax_raw", "ay_raw", "az_raw", "gx_raw", "gy_raw", "gz_raw")]
                if (int(saved["packet_index"]) != i + 1 or int(saved["sample_index"]) != j or
                        int(saved["counter_raw"]) != counter or saved_axes != axes):
                    errors.append(f"IMU packet {i + 1} sample {j}: CSV/raw mismatch")
        if counters:
            if (int(row["first_counter"]) != counters[0] or
                    int(row["last_counter"]) != counters[-1]):
                errors.append(f"IMU packet {i + 1}: index/raw counter mismatch")
            if previous_last is not None:
                advance = (counters[0] - previous_last) & 0xffffffff
                if advance > 32 and advance % 32 == 0:
                    missing_samples += advance // 32 - 1
            previous_last = counters[-1]
    if missing_samples != quality["imu_missing_samples_estimated"]:
        errors.append("IMU missing-sample estimate differs from quality")
    if metadata.get("status") != "COMPLETE":
        errors.append("Episode is not COMPLETE")
    return {"pass": not errors, "video_frames": len(frames), "video_bytes": len(video),
            "imu_packets": len(packets), "imu_samples": len(samples),
            "imu_missing_samples_estimated": missing_samples, "errors": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("episode", type=Path)
    parser.add_argument("--decode", action="store_true", help="fully decode every JPEG using Pillow")
    args = parser.parse_args()
    result = inspect(args.episode, args.decode)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not result["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
