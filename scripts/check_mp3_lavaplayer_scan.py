#!/usr/bin/env python3
"""Check MP3 files against lavaplayer's first-frame scan rule.

The check mirrors lavaplayer's MP3 container probe closely enough to catch
files that throw "Mp3 frame not found." during parseHeaders():

1. Skip up to three leading ID3v2 tags using the declared sync-safe tag size.
2. Search for a MPEG Layer III frame sequence after the skipped tags.
3. Treat the file as failing when that sequence is not fully identified inside
   the scan window used by Mp3TrackProvider.FIRST_FRAME_SCAN_DISTANCE.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterable


DEFAULT_SCAN_DISTANCE = 32768
OLD_SCAN_DISTANCE = 2048
DEFAULT_DEEP_SCAN_DISTANCE = 1024 * 1024
MAX_ID3_TAGS = 3
HEADER_SIZE = 4
CHUNK_SIZE = 64 * 1024

ID3_MAGIC = b"ID3"

SAMPLE_RATE_BASE = (11025, 12000, 8000)
MPEG1_BITRATES = (32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
MPEG2_BITRATES = (8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)


@dataclass(frozen=True)
class Id3Tag:
    start: int
    version: int
    revision: int
    flags: int
    payload_size: int
    end: int


@dataclass(frozen=True)
class ScanResult:
    path: Path
    size: int
    tags: tuple[Id3Tag, ...]
    scan_start: int
    first_frame: int | None
    header: bytes | None
    parse_problem: str | None
    chain_problem: str | None
    detected_container: str | None
    scan_distance: int
    old_scan_distance: int
    deep_scan_distance: int

    @property
    def gap(self) -> int | None:
        return None if self.first_frame is None else self.first_frame - self.scan_start

    @property
    def passes_current_scan(self) -> bool:
        if self.detected_container is not None:
            return True

        if self.parse_problem is not None or self.first_frame is None or self.chain_problem is not None:
            return False

        return self.first_frame + HEADER_SIZE <= self.scan_start + self.scan_distance

    @property
    def passes_old_scan(self) -> bool:
        if self.detected_container is not None:
            return True

        if self.parse_problem is not None or self.first_frame is None or self.chain_problem is not None:
            return False

        return self.first_frame + HEADER_SIZE <= self.scan_start + self.old_scan_distance


def syncsafe4(data: bytes) -> int:
    return (data[0] << 21) | (data[1] << 14) | (data[2] << 7) | data[3]


def is_lavaplayer_mp3_header(header: bytes) -> bool:
    """Match Mp3Decoder.hasFrameSync/isUnsupportedVersion/isValidFrame."""
    if len(header) < HEADER_SIZE:
        return False

    first = header[0]
    second = header[1]
    third = header[2]

    return (
        first == 0xFF
        and (second & 0xE0) == 0xE0
        and ((second & 0x18) >> 3) != 0x01
        and (second & 0x06) == 0x02
        and (third & 0xF0) != 0x00
        and (third & 0xF0) != 0xF0
        and (third & 0x0C) != 0x0C
    )


def mpeg_version(header: bytes) -> tuple[str, int, tuple[int, ...]]:
    version_bits = (header[1] & 0x18) >> 3

    if version_bits == 0:
        return "MPEG-2.5", 1, MPEG2_BITRATES
    if version_bits == 2:
        return "MPEG-2", 2, MPEG2_BITRATES
    if version_bits == 3:
        return "MPEG-1", 4, MPEG1_BITRATES

    raise ValueError("reserved MPEG version")


def frame_size(header: bytes) -> int:
    version_name, sample_rate_multiplier, bitrates = mpeg_version(header)
    del version_name

    bitrate_index = (header[2] & 0xF0) >> 4
    sample_rate_index = (header[2] & 0x0C) >> 2
    has_padding = (header[2] & 0x02) != 0

    bitrate = bitrates[bitrate_index - 1] * 1000
    sample_rate = SAMPLE_RATE_BASE[sample_rate_index] * sample_rate_multiplier
    samples_per_frame = 1152 if sample_rate_multiplier == 4 else 576
    frame_length_multiplier = samples_per_frame // 8

    return frame_length_multiplier * bitrate // sample_rate + (1 if has_padding else 0)


def skip_lavaplayer_id3_tags(handle: BinaryIO, file_size: int) -> tuple[tuple[Id3Tag, ...], int, str | None]:
    tags: list[Id3Tag] = []
    position = 0

    for _ in range(MAX_ID3_TAGS):
        handle.seek(position)
        prefix = handle.read(3)

        if prefix != ID3_MAGIC:
            return tuple(tags), position, None

        rest = handle.read(7)
        if len(rest) < 7:
            return tuple(tags), position, "truncated ID3v2 header"

        major = rest[0]
        revision = rest[1]
        flags = rest[2]

        if major < 2 or major > 5:
            # Mp3TrackProvider has already consumed "ID3" + version + revision
            # when it returns from skipIdv3Tags() in this case.
            return tuple(tags), position + 5, f"unsupported ID3v2 major version {major}"

        payload_size = syncsafe4(rest[3:7])
        tag_end = position + 10 + payload_size

        tags.append(Id3Tag(position, major, revision, flags, payload_size, tag_end))
        position = tag_end

        if position > file_size:
            return tuple(tags), position, "ID3v2 tag extends past end of file"

    return tuple(tags), position, "more than three leading ID3v2 tags"


def find_first_lavaplayer_frame_sequence(
    handle: BinaryIO,
    start: int,
    search_distance: int,
    frame_count: int,
) -> tuple[int | None, bytes | None, str | None]:
    if search_distance < HEADER_SIZE:
        return None, None, None

    handle.seek(start)
    remaining = search_distance
    absolute_position = start
    overlap = b""
    first_rejected: tuple[int, bytes, str] | None = None

    while remaining > 0:
        chunk = handle.read(min(CHUNK_SIZE, remaining))
        if not chunk:
            break

        data = overlap + chunk
        data_base = absolute_position - len(overlap)
        last_start = len(data) - HEADER_SIZE

        for index in range(max(0, last_start + 1)):
            candidate_position = data_base + index

            if candidate_position < start:
                continue

            if candidate_position + HEADER_SIZE > start + search_distance:
                break

            header = data[index:index + HEADER_SIZE]
            if is_lavaplayer_mp3_header(header):
                chain_problem = None
                if frame_count > 1:
                    chain_problem = verify_frame_chain(handle, candidate_position, frame_count)

                if chain_problem is None:
                    return candidate_position, header, None

                if first_rejected is None:
                    first_rejected = (candidate_position, header, chain_problem)

        overlap = data[-(HEADER_SIZE - 1):]
        absolute_position += len(chunk)
        remaining -= len(chunk)
        handle.seek(absolute_position)

    if first_rejected is not None:
        return first_rejected

    return None, None, None


def verify_frame_chain(handle: BinaryIO, first_frame: int, frame_count: int) -> str | None:
    resume_position = handle.tell()
    position = first_frame

    try:
        for frame_index in range(frame_count):
            handle.seek(position)
            header = handle.read(HEADER_SIZE)

            if not is_lavaplayer_mp3_header(header):
                return f"frame {frame_index + 1} header not found at expected offset {position}"

            size = frame_size(header)
            if size < HEADER_SIZE:
                return f"frame {frame_index + 1} has invalid size {size}"

            position += size

        return None
    finally:
        handle.seek(resume_position)


def detect_known_container_at_scan_start(handle: BinaryIO, scan_start: int) -> str | None:
    handle.seek(scan_start)
    signature = handle.read(4)

    if signature == b"fLaC":
        return "flac"

    return None


def analyze_file(
    path: Path,
    scan_distance: int,
    old_scan_distance: int,
    deep_scan_distance: int,
    chain_frames: int,
) -> ScanResult:
    file_size = path.stat().st_size
    search_distance = max(scan_distance, deep_scan_distance)

    with path.open("rb") as handle:
        tags, scan_start, parse_problem = skip_lavaplayer_id3_tags(handle, file_size)
        detected_container = detect_known_container_at_scan_start(handle, scan_start)

        if detected_container is None:
            first_frame, header, chain_problem = find_first_lavaplayer_frame_sequence(
                handle,
                scan_start,
                search_distance,
                chain_frames,
            )
        else:
            first_frame = None
            header = None
            chain_problem = None

    return ScanResult(
        path=path,
        size=file_size,
        tags=tags,
        scan_start=scan_start,
        first_frame=first_frame,
        header=header,
        parse_problem=parse_problem,
        chain_problem=chain_problem,
        detected_container=detected_container,
        scan_distance=scan_distance,
        old_scan_distance=old_scan_distance,
        deep_scan_distance=deep_scan_distance,
    )


def iter_mp3_files(paths: Iterable[Path]) -> Iterable[Path]:
    for path in paths:
        if path.is_dir():
            for child in path.rglob("*"):
                if child.is_file() and child.suffix.lower() == ".mp3":
                    yield child
        elif path.is_file() and path.suffix.lower() == ".mp3":
            yield path


def relative_path(path: Path) -> str:
    try:
        return str(path.relative_to(Path.cwd()))
    except ValueError:
        return str(path)


def describe_result(result: ScanResult) -> str:
    tag_text = f"id3_tags={len(result.tags)} scan_start={result.scan_start}"

    if result.detected_container is not None:
        status = f"NON_MP3_{result.detected_container.upper()}"
        frame_text = f"{result.detected_container} signature at scan_start"
    elif result.first_frame is None:
        status = "FAIL"
        frame_text = f"no lavaplayer-valid Layer III frame within {result.deep_scan_distance} bytes"
    else:
        status = "OK"
        if not result.passes_current_scan:
            status = "FAIL"
        elif not result.passes_old_scan:
            status = "OLD_LIMIT"

        frame_text = (
            f"first_frame={result.first_frame} gap={result.gap} "
            f"header={result.header.hex(' ') if result.header else 'n/a'}"
        )

    details = [status, relative_path(result.path), tag_text, frame_text]

    if result.parse_problem is not None:
        details.append(f"parse_problem={result.parse_problem}")

    if result.chain_problem is not None:
        details.append(f"chain_warning={result.chain_problem}")

    return " | ".join(details)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Scan MP3 files for lavaplayer first-frame scan failures.",
    )
    parser.add_argument(
        "paths",
        nargs="+",
        type=Path,
        help="MP3 files or directories to scan recursively.",
    )
    parser.add_argument(
        "--scan-distance",
        type=int,
        default=DEFAULT_SCAN_DISTANCE,
        help=f"Current lavaplayer first-frame scan distance. Default: {DEFAULT_SCAN_DISTANCE}.",
    )
    parser.add_argument(
        "--old-scan-distance",
        type=int,
        default=OLD_SCAN_DISTANCE,
        help=f"Old scan distance to compare against. Default: {OLD_SCAN_DISTANCE}.",
    )
    parser.add_argument(
        "--deep-scan-distance",
        type=int,
        default=DEFAULT_DEEP_SCAN_DISTANCE,
        help=(
            "How far to keep looking when the current scan window misses, "
            f"for diagnostics. Default: {DEFAULT_DEEP_SCAN_DISTANCE}."
        ),
    )
    parser.add_argument(
        "--chain-frames",
        type=int,
        default=3,
        help=(
            "Warn if this many consecutive frames cannot be followed from the first header. "
            "Use 0 to disable. Default: 3."
        ),
    )
    parser.add_argument(
        "--show-ok",
        action="store_true",
        help="Print files that pass the current and old scan windows too.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    files = sorted(set(iter_mp3_files(args.paths)))
    if not files:
        print("No .mp3 files found.", file=sys.stderr)
        return 2

    results = [
        analyze_file(
            path,
            scan_distance=args.scan_distance,
            old_scan_distance=args.old_scan_distance,
            deep_scan_distance=args.deep_scan_distance,
            chain_frames=args.chain_frames,
        )
        for path in files
    ]

    current_failures = [result for result in results if not result.passes_current_scan]
    old_limit_only = [
        result
        for result in results
        if result.passes_current_scan and not result.passes_old_scan
    ]
    chain_warnings = [
        result
        for result in results
        if result.passes_current_scan and result.chain_problem is not None
    ]
    non_mp3 = [
        result
        for result in results
        if result.detected_container is not None
    ]

    for result in results:
        if (
            args.show_ok
            or result.detected_container is not None
            or not result.passes_current_scan
            or not result.passes_old_scan
            or result.chain_problem
        ):
            print(describe_result(result))

    print(
        "Summary: "
        f"scanned={len(results)} "
        f"current_failures={len(current_failures)} "
        f"old_limit_only={len(old_limit_only)} "
        f"chain_warnings={len(chain_warnings)} "
        f"non_mp3={len(non_mp3)}"
    )

    return 1 if current_failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
