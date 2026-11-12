#!/usr/bin/env python3
"""Compare UI screenshots with the committed references and report the differences.

Usage: screenshot_report.py <current_dir> <reference_dir> <diff_dir>

- new:      a screenshot without reference
- changed:  more than 0.1 % of the pixels differ (diff image written to <diff_dir>)
- missing:  a reference without screenshot
Prints GitHub annotations, writes a Markdown table to $GITHUB_STEP_SUMMARY when set, and
exits 0: differences are for review, not a build failure.
"""
import os
import sys

from PIL import Image, ImageChops

THRESHOLD = 0.001  # share of differing pixels


def main(current_dir, reference_dir, diff_dir):
    os.makedirs(diff_dir, exist_ok=True)
    current = {f for f in os.listdir(current_dir) if f.endswith('.png')} if os.path.isdir(current_dir) else set()
    reference = {f for f in os.listdir(reference_dir) if f.endswith('.png')} if os.path.isdir(reference_dir) else set()
    rows, counts = [], {'same': 0, 'new': 0, 'changed': 0, 'missing': 0}
    for name in sorted(current | reference):
        if name not in reference:
            status, detail = 'new', ''
        elif name not in current:
            status, detail = 'missing', ''
        else:
            a = Image.open(os.path.join(current_dir, name)).convert('RGB')
            b = Image.open(os.path.join(reference_dir, name)).convert('RGB')
            if a.size != b.size:
                status, detail = 'changed', '%dx%d → %dx%d' % (b.size + a.size)
            else:
                diff = ImageChops.difference(a, b).convert('L').point(lambda v: 255 if v > 24 else 0)
                share = sum(1 for v in diff.getdata() if v) / float(a.size[0] * a.size[1])
                if share > THRESHOLD:
                    status, detail = 'changed', '%.2f %%' % (share * 100)
                    overlay = a.copy()
                    red = Image.new('RGB', a.size, (255, 40, 40))
                    overlay.paste(red, mask=diff)
                    overlay.save(os.path.join(diff_dir, name))
                else:
                    status, detail = 'same', ''
        counts[status] += 1
        rows.append((name, status, detail))
        if status in ('changed', 'missing'):
            print('::warning title=Screenshot::%s %s %s' % (name, status, detail))
    summary = 'Screenshots: %(same)d same, %(new)d new, %(changed)d changed, %(missing)d missing' % counts
    print('::notice title=Screenshots::' + summary)
    report = ['## ' + summary, '', '| Screen | Status | Detail |', '| --- | --- | --- |']
    report += ['| %s | %s | %s |' % row for row in rows]
    text = '\n'.join(report) + '\n'
    with open(os.path.join(diff_dir, 'report.md'), 'w', encoding='utf-8') as fh:
        fh.write(text)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as fh:
            fh.write(text)


if __name__ == '__main__':
    main(*sys.argv[1:4])
