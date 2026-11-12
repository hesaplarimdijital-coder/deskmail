#!/usr/bin/env python3
"""Turn build failures into GitHub check-run annotations.

Usage: annotate_gradle_errors.py <log> [<log> ...]

Reads Gradle/compiler output and prints GitHub workflow commands so that the
first errors of a failed build are visible as annotations on the commit
(readable without downloading the full job log).

Recognised patterns:
  e: file:///abs/path/File.kt:12:5 message            (Kotlin compiler)
  /abs/path/File.java:12: error: message               (javac)
  ERROR: /abs/path/res/values/x.xml:12: AAPT: error: m (aapt2)
  * What went wrong: ... * Try:                         (Gradle summary)
"""
import os
import re
import sys

WORKSPACE = os.environ.get('GITHUB_WORKSPACE', os.getcwd()).rstrip('/') + '/'
MAX_ERRORS = 10      # GitHub keeps 10 error annotations per step
MAX_WARNINGS = 10    # overflow errors are reported as warnings (another 10 slots)

KOTLIN = re.compile(r'^e: (?:file://)?(?P<path>/[^:]+):(?P<line>\d+):(?P<col>\d+) (?P<msg>.+)$')
JAVAC = re.compile(r'^(?P<path>/[^:]+\.java):(?P<line>\d+): error: (?P<msg>.+)$')
AAPT = re.compile(r'^ERROR: ?(?P<path>/[^:]+):(?P<line>\d+): (?:AAPT: )?(?:error: )?(?P<msg>.+)$')


def esc(value):
    return value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def esc_prop(value):
    return esc(value).replace(':', '%3A').replace(',', '%2C')


def rel(path):
    return path[len(WORKSPACE):] if path.startswith(WORKSPACE) else path


def collect(lines):
    found, seen = [], set()
    for raw in lines:
        line = raw.rstrip('\n')
        for pattern in (KOTLIN, JAVAC, AAPT):
            m = pattern.match(line)
            if m:
                key = (m.group('path'), m.group('line'), m.group('msg'))
                if key not in seen:
                    seen.add(key)
                    found.append({'file': rel(m.group('path')), 'line': m.group('line'),
                                  'col': m.groupdict().get('col'), 'msg': m.group('msg').strip()})
                break
    return found


def what_went_wrong(lines):
    blocks, current = [], None
    for raw in lines:
        line = raw.rstrip('\n')
        if line.startswith('* What went wrong:'):
            current = []
            continue
        if current is not None:
            if line.startswith('* Try:') or line.startswith('* Exception is:'):
                blocks.append('\n'.join(l for l in current if l.strip())[:3000])
                current = None
            else:
                current.append(line)
    return blocks


TEST_FAILED = re.compile(r'^(?P<cls>[\w.$]+) > (?P<test>.+) FAILED$')


def failed_tests(lines):
    """Gradle test logging: 'Class > method FAILED' followed by the indented exception."""
    found = []
    for i, raw in enumerate(lines):
        m = TEST_FAILED.match(raw.rstrip('\n'))
        if not m:
            continue
        detail = []
        for nxt in lines[i + 1:i + 16]:
            if not nxt.startswith((' ', '\t')):
                break
            detail.append(nxt.strip())
        found.append((m.group('cls').split('.')[-1] + '.' + m.group('test'), '\n'.join(detail)[:2500]))
    return found


def main(paths):
    lines = []
    for p in paths:
        if os.path.exists(p):
            with open(p, encoding='utf-8', errors='replace') as fh:
                lines.extend(fh.readlines())
    errors = collect(lines)
    for i, e in enumerate(errors[:MAX_ERRORS + MAX_WARNINGS]):
        kind = 'error' if i < MAX_ERRORS else 'warning'
        props = 'file=%s,line=%s' % (esc_prop(e['file']), e['line'])
        if e['col']:
            props += ',col=%s' % e['col']
        print('::%s %s::%s' % (kind, props, esc(e['msg'])))
    tests = failed_tests(lines)
    if tests:
        # one line with every failed test, then the details; overflow goes to warning slots
        print('::error title=%d failed tests::%s' % (len(tests), esc(', '.join(n for n, _ in tests))[:3000]))
        for i, (name, detail) in enumerate(tests[:7 + MAX_WARNINGS]):
            kind = 'error' if i < 7 else 'warning'
            print('::%s title=Test %s::%s' % (kind, esc_prop(name), esc(detail or name)))
    for block in what_went_wrong(lines)[:2]:
        print('::error title=Gradle::%s' % esc(block))
    if not errors and not tests and not what_went_wrong(lines):
        tail = ''.join(lines[-40:])
        print('::error title=Build log tail::%s' % esc(tail[-3000:]))
    print('annotated %d compiler errors' % len(errors))


if __name__ == '__main__':
    main(sys.argv[1:])
