#!/usr/bin/env python3
#
# Copyright (C) 2026 The 2by2 Project
#
# SPDX-License-Identifier: Apache-2.0
#
"""Updates native/ to an upstream Mozc release.

  tools/update_mozc.py [--tag 3.35.6354 | --tag latest] [--mozc_src DIR]

Steps:
  1. Check out google/mozc at the given tag (or use --mozc_src).
  2. Fetch the external data archives pinned in MODULE.bazel (usage
     dictionary, zip codes) and verify their sha256.
  3. Regenerate native/ (sources + Android.bp) with bazel2bp.py.
  4. Regenerate the Java client data derived from upstream (proto sources).
"""

import argparse
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

TOOLS = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(TOOLS)
NATIVE = os.path.join(REPO, 'native')
UPSTREAM = 'https://github.com/google/mozc.git'

# name in MODULE.bazel -> (member in archive, destination in native/)
ARCHIVES = {
    'ja_usage_dict': ('usage_dict.txt',
                      'third_party/japanese_usage_dictionary/usage_dict.txt'),
    'zip_code_ken_all': ('KEN_ALL.CSV', 'third_party/zip_code/KEN_ALL.CSV'),
    'zip_code_jigyosyo': ('JIGYOSYO.CSV', 'third_party/zip_code/JIGYOSYO.CSV'),
}
# License files to keep next to the fetched data.
ARCHIVE_LICENSES = {
    'ja_usage_dict': ('LICENSE', 'third_party/japanese_usage_dictionary/LICENSE'),
}


# ---- Safe file handling -------------------------------------------------------
#
# This script never deletes directories recursively. Every file it writes into
# native/ is recorded in native/FILES; on the next update only files listed
# there (and not produced again) are removed, one by one, after checking that
# they are regular files inside native/.

FILES_LIST = 'FILES'


def native_path(rel):
  """Returns the absolute path of rel inside native/, or raises."""
  if os.path.isabs(rel) or '\\' in rel:
    raise ValueError('refusing path %r' % rel)
  norm = os.path.normpath(rel)
  if norm.startswith('..') or norm == '.':
    raise ValueError('refusing path %r' % rel)
  path = os.path.join(NATIVE, norm)
  # Do not follow symlinks that could point outside native/.
  real_parent = os.path.realpath(os.path.dirname(path))
  if not (real_parent + os.sep).startswith(os.path.realpath(NATIVE) + os.sep) and \
      real_parent != os.path.realpath(NATIVE):
    raise ValueError('refusing path %r (outside native/)' % rel)
  return path


class Written(object):
  """Records the files written into native/."""

  def __init__(self):
    self.files = set()

  def write(self, rel, data):
    path = native_path(rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
      f.write(data)
    self.files.add(os.path.normpath(rel))

  def copy(self, src, rel):
    with open(src, 'rb') as f:
      self.write(rel, f.read())


def read_files_list():
  path = os.path.join(NATIVE, FILES_LIST)
  if not os.path.exists(path):
    return set()
  with open(path, encoding='utf-8') as f:
    return {l.strip() for l in f if l.strip()}


def remove_stale(old, new):
  """Removes files generated last time but not this time."""
  for rel in sorted(old - new):
    path = native_path(rel)
    if os.path.islink(path) or not os.path.isfile(path):
      print('skip (not a regular file):', rel)
      continue
    os.remove(path)
    print('removed stale file:', rel)
    # Remove now-empty parent directories (never non-empty ones).
    d = os.path.dirname(path)
    while d != NATIVE and not os.listdir(d):
      os.rmdir(d)
      d = os.path.dirname(d)


def run(cmd, **kw):
  print('+', ' '.join(cmd), flush=True)
  return subprocess.run(cmd, check=True, **kw)


def latest_tag():
  out = subprocess.run(['git', 'ls-remote', '--tags', '--refs', UPSTREAM],
                       check=True, capture_output=True, text=True).stdout
  tags = re.findall(r'refs/tags/(\d+\.\d+\.\d+(?:\.\d+)?)$', out, re.M)
  return max(tags, key=lambda t: tuple(int(x) for x in t.split('.')))


def checkout(tag, workdir):
  dst = os.path.join(workdir, 'mozc')
  run(['git', 'clone', '--quiet', '--depth=1', '--branch', tag, UPSTREAM, dst])
  return os.path.join(dst, 'src')


def module_archives(mozc_src):
  """Parses http_archive() entries (name, url, sha256, strip_prefix)."""
  text = open(os.path.join(mozc_src, 'MODULE.bazel'), encoding='utf-8').read()
  consts = dict(re.findall(r'^([A-Z_]+) = "([^"]*)"', text, re.M))
  out = {}
  for block in re.findall(r'http_archive\((.*?)\n\)', text, re.S):
    def field(key):
      m = re.search(r'\b' + key + r' = ("[^"]*"(?: % [A-Z_]+)?)', block)
      if not m:
        return None
      expr = m.group(1)
      m2 = re.match(r'"([^"]*)" % ([A-Z_]+)', expr)
      if m2:
        return m2.group(1) % consts[m2.group(2)]
      return expr.strip('"')
    out[field('name')] = (field('url'), field('sha256'), field('strip_prefix'))
  return out


def fetch_archives(mozc_src, written):
  archives = module_archives(mozc_src)
  for name, (member, dest) in ARCHIVES.items():
    url, sha256, prefix = archives[name]
    print('fetching', url, flush=True)
    data = urllib.request.urlopen(url, timeout=120).read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != sha256:
      sys.exit('sha256 mismatch for %s: %s != %s' % (url, digest, sha256))
    z = zipfile.ZipFile(io.BytesIO(data))
    wanted = [(member, dest)]
    if name in ARCHIVE_LICENSES:
      wanted.append(ARCHIVE_LICENSES[name])
    for m, d in wanted:
      path = '%s/%s' % (prefix, m) if prefix else m
      written.write(d, z.read(path))


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument('--tag', default='latest')
  ap.add_argument('--mozc_src', help='use an existing checkout (…/mozc/src)')
  args = ap.parse_args()

  with tempfile.TemporaryDirectory() as tmp:
    tag = args.tag
    if args.mozc_src:
      src = os.path.abspath(args.mozc_src)
    else:
      if tag == 'latest':
        tag = latest_tag()
      src = checkout(tag, tmp)
    version = tag

    if os.path.exists(NATIVE) and not os.path.exists(os.path.join(NATIVE, FILES_LIST)) \
        and os.listdir(NATIVE):
      sys.exit('native/ exists but has no %s list; refusing to touch it' % FILES_LIST)
    os.makedirs(NATIVE, exist_ok=True)
    old_files = read_files_list()
    written = Written()

    fetch_archives(src, written)
    copied_list = os.path.join(tmp, 'copied.txt')
    run([sys.executable, '-I', os.path.join(TOOLS, 'bazel2bp.py'),
         '--mozc_src', src, '--out', NATIVE, '--version', version,
         '--prologue', os.path.join(TOOLS, 'native_prologue.bp'),
         '--written_list', copied_list])
    with open(copied_list, encoding='utf-8') as f:
      written.files.update(l.strip() for l in f if l.strip())
    for name in ('LICENSE', 'AUTHORS'):
      for cand in (os.path.join(src, name), os.path.join(src, '..', name)):
        if os.path.exists(cand):
          written.copy(cand, name)
          break
    shims = os.path.join(TOOLS, 'shims')
    for dirpath, _, filenames in os.walk(shims):
      for name in filenames:
        full = os.path.join(dirpath, name)
        written.copy(full, os.path.join('shims', os.path.relpath(full, shims)))
    # The linker version script lives in tools/ (it is ours, not upstream's).
    written.copy(os.path.join(TOOLS, 'libmozc.map'), 'android/jni/libmozc.map')
    written.write('VERSION', (version + '\n').encode())

    remove_stale(old_files, written.files | {FILES_LIST})
    written.write(FILES_LIST, ''.join(
        f + '\n' for f in sorted(written.files | {FILES_LIST})).encode())

    # Open source credits shown from the "About" preference page.
    os.makedirs(os.path.join(REPO, 'assets'), exist_ok=True)
    shutil.copy2(os.path.join(src, 'data', 'installer', 'credits_en.html'),
                 os.path.join(REPO, 'assets', 'credits_en.html'))
  print('Updated native/ to Mozc', version)


if __name__ == '__main__':
  main()
