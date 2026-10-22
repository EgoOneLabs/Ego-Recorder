#!/usr/bin/env python3
"""Inspect an EgoUVC-Phone V0.2.1 episode copied from Android (stdlib-only)."""
import argparse
import csv
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("episode", type=Path)
    args = parser.parse_args()
    root = args.episode
    metadata = json.loads((root / "metadata.json").read_text())
    quality = json.loads((root / "quality.json").read_text())
    frames = list(csv.DictReader((root / "video_frames.csv").open(newline="")))
    packets = list(csv.DictReader((root / "imu_packets.csv").open(newline="")))
    samples = list(csv.DictReader((root / "imu_samples.csv").open(newline="")))
    raw = (root / "imu_packets.bin").read_bytes()
    valid_jpg = 0
    for entry in frames:
        data = (root / entry["filename"]).read_bytes()
        valid_jpg += data[:2] == b"\xff\xd8" and data[-2:] == b"\xff\xd9"
    bad_headers = 0
    for off in range(0, len(raw), 18):
        if raw[off:off+2] != b"\xde\xc8":
            bad_headers += 1
    expected_video = quality["saved_jpeg_frames"]
    expected_imu = quality["imu_packets"]
    print("Episode:", root)
    print("Metadata:", metadata["schema"], "complete:", metadata["complete"])
    print("Video:", len(frames), "indexed JPEG frames,", valid_jpg, "valid JPEG headers/ends,", expected_video, "reported")
    print("IMU:", len(packets), "indexed 252-byte packets,", len(samples), "samples,", expected_imu, "reported")
    print("Raw IMU:", len(raw), "bytes,", bad_headers, "bad record headers")
    print("IMU error:", quality.get("imu_error"))
    print("Video error:", quality.get("video_error"))
    print("Dropped:", quality.get("video_backpressure_dropped"), "Missing IMU:", quality.get("imu_missing_samples_estimated"))
    if len(frames) != expected_video or valid_jpg != len(frames):
        raise SystemExit("Video count/content mismatch")
    if len(packets) != expected_imu or len(raw) != len(packets) * 252 or len(samples) != len(packets) * 14 or bad_headers:
        raise SystemExit("IMU count/content mismatch")
    if not metadata["complete"]:
        raise SystemExit("Episode is partial (review quality.json)")
    print("PASS: indexed JPEG + IMU packet structure")


if __name__ == "__main__":
    main()
