#!/usr/bin/env python3
"""Builds app/src/main/assets/lists/adult-domains.txt: the adult-site list, most common site first.

The app loads "the first N lines" of that file, so the order is what makes a smaller list useful.

  python -I tools/make-adult-list.py --hosts <StevenBlack porn hosts file> --rank <majestic_million.csv> --out <list>

Inputs (both are only read here, neither is shipped in the app):
  * StevenBlack/hosts, "porn" extension (MIT)            - which sites are adult
  * Majestic Million (CC BY 3.0, https://majestic.com)   - how widely known each site is

What it does:
  1. reads the hostnames and removes the ones a parent entry already covers (the app blocks subdomains too);
  2. gives every entry the rank of its own domain in the Majestic Million (a subdomain gets no rank);
  3. orders them in four groups, each by popularity / name length:
       a. adult-looking name AND ranked      (pornhub.com, xvideos.com ...)  <- what "top N" is mostly made of
       b. adult-looking name, not ranked     (obscure, but clearly adult)
       c. other name, ranked                 (mixed-content sites, brand names like fc2.com, and a few list mistakes)
       d. everything else
     The "adult-looking name" test is only used for ordering; it never adds or removes a site.
  4. writes one domain per line.
"""
import argparse
import csv
import datetime
import re
import sys

HOST_OK = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

# Words that make a domain name look adult. Only used to order the list, never to add or drop a site.
ADULT_WORDS = (
    "porn", "xxx", "sex", "nude", "naked", "hentai", "fuck", "milf", "anal", "slut", "whore", "boob", "tits", "pussy",
    "escort", "erotic", "erotik", "erotica", "nsfw", "bdsm", "fetish", "kink", "swinger", "stripper", "strip",
    "camgirl", "livecam", "webcam", "cams", "xvideo", "xnxx", "xham", "xtube", "tube8", "redtube", "youporn", "spankbang",
    "eporner", "tnaflix", "beeg", "txxx", "hclips", "motherless", "rule34", "nhentai", "gelbooru", "imagefap",
    "onlyfans", "fansly", "chaturbate", "stripchat", "bongacams", "camsoda", "myfreecams", "livejasmin", "cam4",
    "brazzers", "bangbros", "realitykings", "naughty", "playboy", "penthouse", "hustler", "pervert", "incest",
    "lust", "lewd", "horny", "squirt", "orgasm", "nympho", "voyeur", "upskirt", "gonzo", "bukkake", "jizz", "cumshot",
    "hotgirl", "sexy", "xxnx", "pornhub", "adultfriend", "ashley", "cuckold", "shemale", "tranny", "ladyboy",
)


def suffixes(host):
    labels = host.split(".")
    return [".".join(labels[i:]) for i in range(len(labels) - 1)]  # never a bare TLD


def looks_adult(host):
    return any(w in host for w in ADULT_WORDS)


def read_hosts(path):
    out = set()
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            host = parts[1].lower().strip(".")
            if host in ("localhost", "0.0.0.0") or "." not in host or not HOST_OK.match(host):
                continue
            out.add(host)
    return out


def read_rank(path):
    rank = {}
    with open(path, encoding="utf-8", errors="replace", newline="") as f:
        rd = csv.reader(f)
        header = next(rd)
        gi, di = header.index("GlobalRank"), header.index("Domain")
        for row in rd:
            try:
                rank[row[di].lower()] = int(row[gi])
            except (IndexError, ValueError):
                continue
    return rank


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hosts", required=True)
    ap.add_argument("--rank", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    hosts = read_hosts(a.hosts)
    rank = read_rank(a.rank)
    print(f"hosts read: {len(hosts):,}   ranking rows: {len(rank):,}")

    # 1. drop entries whose parent domain is also listed (blocking a domain blocks its subdomains in the app)
    kept = sorted(h for h in hosts if not any(s in hosts for s in suffixes(h)[1:]))
    print(f"after removing subdomains of listed domains: {len(kept):,}")

    # 2./3. rank and group
    def rank_of(h):
        return rank.get(h[4:] if h.startswith("www.") else h)

    scored = []
    groups = [0, 0, 0, 0]
    for h in kept:
        r = rank_of(h)
        adult = looks_adult(h)
        if adult and r is not None:
            g, key = 0, r
        elif adult:
            g, key = 1, len(h)
        elif r is not None:
            g, key = 2, r
        else:
            g, key = 3, len(h)
        groups[g] += 1
        scored.append((g, key, h))
    scored.sort()

    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Willbolt adult-site list, most common first. Built %s by tools/make-adult-list.py\n" % datetime.date.today())
        f.write("# Which sites: StevenBlack/hosts, porn extension (MIT) - https://github.com/StevenBlack/hosts\n")
        f.write("# Order: Majestic Million ranking (CC BY 3.0) - https://majestic.com/reports/majestic-million\n")
        f.write("# A domain also covers all of its subdomains.\n")
        f.write("# entries: %d\n" % len(scored))
        for _, _, h in scored:
            f.write(h + "\n")

    print(f"written: {len(scored):,} entries -> {a.out}")
    print(f"groups: adult+ranked {groups[0]:,} | adult name only {groups[1]:,} | ranked only {groups[2]:,} | rest {groups[3]:,}")
    print("first 60:", ", ".join(s[2] for s in scored[:60]))
    cut = groups[0]
    print(f"around the end of group a ({cut:,}):", ", ".join(s[2] for s in scored[max(0, cut - 8):cut]))
    print("group c head:", ", ".join(s[2] for s in scored[groups[0] + groups[1]:groups[0] + groups[1] + 25]))


if __name__ == "__main__":
    sys.exit(main())
