#!/usr/bin/env python3
"""Turn failed unit tests into GitHub annotations.

Usage: annotate_test_failures.py <test-results-dir> [...]

Reads the JUnit XML reports written by Gradle and prints one error annotation per failed
test with the first lines of its message, so failures are readable on the commit page.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX = 10


def esc(value):
    return value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def main(dirs):
    count = 0
    for d in dirs:
        for path in sorted(glob.glob(os.path.join(d, '**', 'TEST-*.xml'), recursive=True)):
            root = ET.parse(path).getroot()
            for case in root.iter('testcase'):
                for failure in list(case.findall('failure')) + list(case.findall('error')):
                    count += 1
                    if count > MAX:
                        continue
                    text = (failure.get('message') or '') + '\n' + (failure.text or '')
                    lines = [l for l in text.strip().splitlines() if l.strip()][:25]
                    title = '%s.%s' % (case.get('classname', '').split('.')[-1], case.get('name'))
                    print('::error title=%s::%s' % (esc(title), esc('\n'.join(lines))))
    print('::notice title=Tests::%d failed test(s)' % count)


if __name__ == '__main__':
    main(sys.argv[1:])
