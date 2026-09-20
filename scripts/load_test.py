#!/usr/bin/env python3
"""Simple concurrent load test for ShortLinkService."""

import argparse
import hashlib
import json
import random
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


class LinkPool:
    def __init__(self) -> None:
        self.ids: list[str] = []
        self.lock = threading.Lock()

    def add(self, short_id: str) -> None:
        with self.lock:
            self.ids.append(short_id)

    def random_id(self, rng: random.Random) -> Optional[str]:
        with self.lock:
            return rng.choice(self.ids) if self.ids else None


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

    def create_link(self, worker: int, sequence: int) -> Optional[str]:
        timestamp = int(time.time())
        unique_url = f"http://example.com/load-test/{worker}-{time.time_ns()}-{sequence}"
        captcha = solve_pow(unique_url, self.expire_after, timestamp, self.difficulty)

        response, created = self.request(
            "POST /shorten",
            "/shorten",
            {"url": unique_url, "expireAfter": str(self.expire_after), "updateCode": "", "captcha": captcha, "time": str(timestamp)},
        )
        if not created or not isinstance(response, dict):
            return None
        short_id = response.get("data")
        if not short_id:
            return None
        return urllib.parse.quote(str(short_id), safe="")

    def run_action(self, worker: int, sequence: int, pool: LinkPool, rng: random.Random,
                   redirect_ratio: float, info_ratio: float, create_ratio: float) -> None:
        roll = rng.random()
        if roll < create_ratio:
            short_id = self.create_link(worker, sequence)
            if short_id:
                pool.add(short_id)
            return

        short_id = pool.random_id(rng)
        if not short_id:
            return
        if roll < create_ratio + info_ratio:
            self.request("GET /getInfo/{id}", f"/getInfo/{short_id}")
        elif roll < create_ratio + info_ratio + redirect_ratio:
            self.request("GET /{id}", f"/{short_id}")


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
    parser.add_argument("--actions", type=int, default=100, help="steady-state actions per worker")
    parser.add_argument("--seed-links", type=int, default=20, help="links created before steady-state traffic")
    parser.add_argument("--ramp-up", type=float, default=0, help="seconds to gradually start workers")
    parser.add_argument("--redirect-ratio", type=float, default=0.95)
    parser.add_argument("--info-ratio", type=float, default=0.04)
    parser.add_argument("--create-ratio", type=float, default=0.01)
    parser.add_argument("--think-time-ms", type=float, default=0, help="average exponential pause between actions")
    parser.add_argument("--expire-after", type=int, default=3600)
    parser.add_argument("--pow-difficulty", type=int, default=2)
    parser.add_argument("--timeout", type=float, default=10)
    args = parser.parse_args()
    if args.users < 1 or args.actions < 1 or args.seed_links < 1 or args.pow_difficulty < 0:
        parser.error("users, actions and seed-links must be positive; pow-difficulty must be non-negative")
    if min(args.redirect_ratio, args.info_ratio, args.create_ratio, args.think_time_ms) < 0:
        parser.error("ratios and think-time-ms must not be negative")
    if abs(args.redirect_ratio + args.info_ratio + args.create_ratio - 1.0) > 1e-9:
        parser.error("redirect-ratio + info-ratio + create-ratio must equal 1")

    metrics = Metrics()
    tester = LoadTester(args.base_url, args.expire_after, args.pow_difficulty, args.timeout, metrics)
    pool = LinkPool()
    started = time.perf_counter()

    def seed_worker(seed_number: int) -> None:
        short_id = tester.create_link(seed_number % args.users, seed_number)
        if short_id:
            pool.add(short_id)

    with ThreadPoolExecutor(max_workers=args.users) as executor:
        futures = [executor.submit(seed_worker, seed_number) for seed_number in range(args.seed_links)]
        for future in as_completed(futures):
            future.result()

    if not pool.ids:
        print("No short links were created during seeding; cannot run read traffic.")
        raise SystemExit(1)

    def worker(worker_id: int) -> None:
        rng = random.Random(time.time_ns() ^ (worker_id << 16))
        if args.ramp_up and worker_id:
            time.sleep(args.ramp_up * worker_id / args.users)
        for sequence in range(args.actions):
            tester.run_action(worker_id, sequence, pool, rng, args.redirect_ratio, args.info_ratio, args.create_ratio)
            if args.think_time_ms:
                time.sleep(rng.expovariate(1000 / args.think_time_ms))

    with ThreadPoolExecutor(max_workers=args.users) as executor:
        futures = [executor.submit(worker, worker_id) for worker_id in range(args.users)]
        for future in as_completed(futures):
            future.result()

    print_summary(metrics, time.perf_counter() - started)
    if any(not sample.ok for sample in metrics.samples):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
