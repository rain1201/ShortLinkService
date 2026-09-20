#!/usr/bin/env python3
"""Simple concurrent load test for ShortLinkService."""

import argparse
import hashlib
import json
import statistics
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Optional


@dataclass
class Sample:
    name: str
    elapsed_ms: float
    ok: bool
    status: Optional[int] = None
    error: str = ""


class Metrics:
    def __init__(self) -> None:
        self.samples: list[Sample] = []
        self.lock = threading.Lock()

    def add(self, sample: Sample) -> None:
        with self.lock:
            self.samples.append(sample)


def solve_pow(url: str, expire_after: int, timestamp: int, difficulty: int) -> str:
    prefix = f"url{url}expireAfter{expire_after}{timestamp}"
    nonce = 0
    while True:
        digest = hashlib.sha1(f"{prefix}{nonce}".encode()).digest()
        if digest[:difficulty] == b"\x00" * difficulty:
            return str(nonce)
        nonce += 1


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LoadTester:
    def __init__(self, base_url: str, expire_after: int, difficulty: int, timeout: float, metrics: Metrics):
        self.base_url = base_url.rstrip("/")
        self.expire_after = expire_after
        self.difficulty = difficulty
        self.timeout = timeout
        self.metrics = metrics
        self.opener = urllib.request.build_opener(NoRedirectHandler)

    def request(self, name: str, path: str, data: Optional[dict] = None) -> tuple[Optional[object], bool]:
        body = None
        headers = {"User-Agent": "ShortLinkService-PythonLoadTest"}
        if data is not None:
            body = urllib.parse.urlencode(data).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        request = urllib.request.Request(self.base_url + path, data=body, headers=headers)
        started = time.perf_counter()
        status = None
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                status = response.status
                raw = response.read()
            elapsed = (time.perf_counter() - started) * 1000
            parsed = json.loads(raw.decode("utf-8")) if raw else None
            ok = 200 <= status < 300 and (not isinstance(parsed, dict) or parsed.get("code") == 0)
            self.metrics.add(Sample(name, elapsed, ok, status, "" if ok else "unexpected response"))
            return parsed, ok
        except urllib.error.HTTPError as exc:
            elapsed = (time.perf_counter() - started) * 1000
            redirect_ok = name == "GET /{id}" and 300 <= exc.code < 400
            self.metrics.add(Sample(name, elapsed, redirect_ok, exc.code, "" if redirect_ok else str(exc.reason)))
            return None, redirect_ok
        except Exception as exc:  # noqa: BLE001 - a load test must record request failures
            elapsed = (time.perf_counter() - started) * 1000
            self.metrics.add(Sample(name, elapsed, False, status, str(exc)))
            return None, False

    def run_iteration(self, worker: int, iteration: int, reads_per_write: int, info_every: int) -> None:
        timestamp = int(time.time())
        unique_url = f"http://example.com/load-test/{worker}-{timestamp}-{iteration}"
        captcha = solve_pow(unique_url, self.expire_after, timestamp, self.difficulty)

        response, created = self.request(
            "POST /shorten",
            "/shorten",
            {"url": unique_url, "expireAfter": str(self.expire_after), "updateCode": "", "captcha": captcha, "time": str(timestamp)},
        )
        if not created or not isinstance(response, dict):
            return
        short_id = response.get("data")
        if not short_id:
            return
        encoded_id = urllib.parse.quote(str(short_id), safe="")

        # A short-link service is read-heavy: one link creation is followed by
        # many redirect requests. Metadata reads are sampled less frequently.
        for read_number in range(1, reads_per_write + 1):
            self.request("GET /{id}", f"/{encoded_id}")
            if read_number % info_every == 0:
                self.request("GET /getInfo/{id}", f"/getInfo/{encoded_id}")


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * ratio))))
    return ordered[index]


def print_summary(metrics: Metrics, duration: float) -> None:
    by_name: dict[str, list[Sample]] = {}
    for sample in metrics.samples:
        by_name.setdefault(sample.name, []).append(sample)

    print("\n=== ShortLinkService load test summary ===")
    print(f"Elapsed: {duration:.2f}s")
    print(f"Requests: {len(metrics.samples)}")
    print(f"Failures: {sum(not sample.ok for sample in metrics.samples)}")
    print(f"Throughput: {len(metrics.samples) / duration:.2f} req/s" if duration else "Throughput: n/a")
    print("\nName                 Count   Error%    Avg(ms)   P95(ms)   Max(ms)")
    print("-" * 72)
    for name, samples in by_name.items():
        values = [sample.elapsed_ms for sample in samples]
        failures = sum(not sample.ok for sample in samples)
        print(f"{name:<20} {len(samples):>5}   {failures / len(samples) * 100:>6.2f}   {statistics.mean(values):>8.2f}   {percentile(values, .95):>8.2f}   {max(values):>8.2f}")
    failed = [sample for sample in metrics.samples if not sample.ok]
    if failed:
        print("\nFailures (up to 10):")
        for sample in failed[:10]:
            print(f"- {sample.name}: HTTP {sample.status or '-'}; {sample.error or 'unexpected response'}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Concurrent load test for ShortLinkService")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--users", type=int, default=10, help="concurrent workers")
    parser.add_argument("--loops", type=int, default=10, help="iterations per worker")
    parser.add_argument("--ramp-up", type=float, default=0, help="seconds to gradually start workers")
    parser.add_argument("--reads-per-write", type=int, default=20, help="redirects after each created link")
    parser.add_argument("--info-every", type=int, default=5, help="query metadata once every N redirects")
    parser.add_argument("--expire-after", type=int, default=3600)
    parser.add_argument("--pow-difficulty", type=int, default=2)
    parser.add_argument("--timeout", type=float, default=10)
    args = parser.parse_args()
    if args.users < 1 or args.loops < 1 or args.reads_per_write < 1 or args.info_every < 1 or args.pow_difficulty < 0:
        parser.error("users, loops, reads-per-write and info-every must be positive; pow-difficulty must be non-negative")

    metrics = Metrics()
    tester = LoadTester(args.base_url, args.expire_after, args.pow_difficulty, args.timeout, metrics)
    started = time.perf_counter()

    def worker(worker_id: int) -> None:
        if args.ramp_up and worker_id:
            time.sleep(args.ramp_up * worker_id / args.users)
        for iteration in range(args.loops):
            tester.run_iteration(worker_id, iteration, args.reads_per_write, args.info_every)

    with ThreadPoolExecutor(max_workers=args.users) as executor:
        futures = [executor.submit(worker, worker_id) for worker_id in range(args.users)]
        for future in as_completed(futures):
            future.result()

    print_summary(metrics, time.perf_counter() - started)
    if any(not sample.ok for sample in metrics.samples):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
