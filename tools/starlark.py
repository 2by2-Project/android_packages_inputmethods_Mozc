#
# Copyright (C) 2026 The 2by2 Project
#
# SPDX-License-Identifier: Apache-2.0
#
"""Tiny Bazel loading-phase emulator for the Mozc source tree.

Starlark is (almost) a subset of Python, so BUILD.bazel and .bzl files are
exec()'d with stub builtins.  Every rule invocation is recorded as a Target,
which bazel2bp.py later turns into Soong modules.

select() values are kept symbolic (Sel) and resolved per configuration.
"""

import glob as _glob
import os
import re


class Sel(object):
  """Symbolic concatenation of plain values and select() dicts."""

  def __init__(self, parts):
    self.parts = parts

  def __add__(self, other):
    return Sel(self.parts + _parts(other))

  def __radd__(self, other):
    return Sel(_parts(other) + self.parts)

  def __iter__(self):
    raise TypeError('cannot iterate over unresolved select()')

  def resolve(self, cfg):
    out = None
    for p in self.parts:
      if isinstance(p, dict):
        p = resolve(_pick(p, cfg), cfg)
      if out is None:
        out = p
      else:
        out = out + p
    return out


def _parts(v):
  return v.parts if isinstance(v, Sel) else [v]


def _pick(d, cfg):
  for key, value in d.items():
    if key != '//conditions:default' and cfg.matches(key):
      return value
  if '//conditions:default' in d:
    return d['//conditions:default']
  raise KeyError('no select() branch matches: %r' % list(d))


def resolve(v, cfg):
  if isinstance(v, Sel):
    return v.resolve(cfg)
  if isinstance(v, list):
    out = []
    for x in v:
      x = resolve(x, cfg)
      out.append(x)
    return out
  if isinstance(v, dict):
    return {resolve(k, cfg): resolve(x, cfg) for k, x in v.items()}
  return v


class Config(object):
  """A build configuration: which config_settings are true."""

  def __init__(self, name, true_settings):
    self.name = name
    self.true = set(true_settings)

  def matches(self, label):
    return label in self.true


class Struct(object):

  def __init__(self, **kw):
    self.__dict__.update(kw)

  def to_json(self):
    return repr(self.__dict__)


class Target(object):

  def __init__(self, kind, pkg, attrs):
    self.kind = kind
    self.pkg = pkg
    self.name = attrs.get('name')
    self.attrs = attrs

  @property
  def label(self):
    return '//%s:%s' % (self.pkg, self.name)

  def __repr__(self):
    return '<%s %s>' % (self.kind, self.label)


class _Native(object):
  """The `native` module available in .bzl files."""

  def __init__(self, loader):
    self._loader = loader

  def __getattr__(self, kind):
    if kind == 'package_name':
      return lambda: self._loader.cur_pkg
    if kind == 'repository_name':
      return lambda: '@'
    if kind == 'glob':
      return self._loader.glob
    if kind == 'package_relative_label':
      return self._loader._package_relative_label
    if kind == 'existing_rule':
      return lambda name: None
    return self._loader.rule_fn(kind)


class _Attr(object):

  def __getattr__(self, name):
    return lambda *a, **kw: None


EXTERNAL_RULES = {
    # Loaded from external repositories; they become plain recorded rules.
    'cc_library', 'cc_binary', 'cc_test', 'cc_proto_library',
    'proto_library', 'py_binary', 'py_library', 'py_test', 'run_binary',
    'java_lite_proto_library', 'objc_library', 'diff_test', 'bool_flag',
    'string_flag', 'macos_application', 'macos_bundle', 'macos_unit_test',
    'ios_application', 'apple_genrule', 'pkg_tar', 'pkg_files',
    'pkg_zip', 'android_binary', 'android_library',
}


class Loader(object):

  def __init__(self, root):
    self.root = root
    self.targets = {}  # label -> Target
    self.cur_pkg = None
    self.bzl_cache = {}
    self.loaded_pkgs = set()

  # ---- rules -------------------------------------------------------------
  def rule_fn(self, kind):
    def fn(*args, **attrs):
      if args and kind not in ('exports_files', 'licenses', 'package'):
        raise TypeError('%s takes keyword args only' % kind)
      if kind in ('package', 'licenses', 'package_group', 'exports_files',
                  'test_suite', 'config_setting', 'platform',
                  'constraint_value', 'constraint_setting'):
        if kind == 'exports_files':
          return None
        if 'name' not in attrs:
          return None
      t = Target(kind, self.cur_pkg, attrs)
      self.targets[t.label] = t
      return None
    return fn

  def custom_rule(self, implementation=None, **_):
    kind = implementation.__name__ if implementation else 'custom_rule'
    kind = kind.lstrip('_')
    if kind.endswith('_impl'):
      kind = kind[:-len('_impl')]
    return self.rule_fn(kind)

  def glob(self, include, exclude=None, allow_empty=True, **_):
    base = os.path.join(self.root, self.cur_pkg)
    out = set()
    for pat in include:
      for f in _glob.glob(os.path.join(base, pat), recursive=True):
        if os.path.isfile(f):
          out.add(os.path.relpath(f, base))
    for pat in exclude or []:
      for f in _glob.glob(os.path.join(base, pat), recursive=True):
        out.discard(os.path.relpath(f, base))
    return sorted(out)

  # ---- loading ------------------------------------------------------------
  def _globals(self):
    g = {
        'select': lambda d, no_match_error=None: Sel([d]),
        'glob': self.glob,
        'native': _Native(self),
        'struct': Struct,
        'provider': lambda *a, **kw: Struct,
        'rule': self.custom_rule,
        'aspect': lambda *a, **kw: None,
        'attr': _Attr(),
        'Label': lambda s: s,
        'fail': self._fail,
        'print': lambda *a, **kw: None,
        'package_name': lambda: self.cur_pkg,
        'package_relative_label': self._package_relative_label,
        'depset': lambda *a, **kw: [],
        'DefaultInfo': Struct,
        'load': self._load,
        'module_name': lambda: 'mozc',
        'True': True, 'False': False, 'None': None,
        'transition': lambda *a, **kw: None,
        'exec_group': lambda *a, **kw: None,
        'config_common': Struct(toolchain_type=lambda *a, **kw: None),
        'apple_common': Struct(),
        'CcInfo': Struct,
    }
    for kind in ('package', 'licenses', 'package_group', 'exports_files',
                 'genrule', 'filegroup', 'test_suite', 'config_setting',
                 'alias', 'sh_binary', 'sh_test', 'platform'):
      g[kind] = self.rule_fn(kind)
    return g

  def _package_relative_label(self, s):
    if s.startswith('//') or s.startswith('@'):
      return s
    return '//%s:%s' % (self.cur_pkg, s.lstrip(':'))

  def _fail(self, *msg, **_):
    raise RuntimeError('fail(): %s' % ' '.join(str(m) for m in msg))

  def _bzl_path(self, label):
    label = label.lstrip('@')
    if label.startswith('//'):
      pkg, _, name = label[2:].partition(':')
      return os.path.join(self.root, pkg, name)
    if label.startswith(':'):
      return os.path.join(self.root, self.cur_pkg, label[1:])
    return None

  def _load(self, label, *symbols, **aliases):
    raise RuntimeError('load() must be handled by the preprocessor')

  def load_bzl(self, label):
    if label.startswith('@') and not label.startswith('@//'):
      return None  # external repository
    path = self._bzl_path(label)
    path = os.path.normpath(path)
    if path in self.bzl_cache:
      return self.bzl_cache[path]
    g = self._globals()
    self.bzl_cache[path] = g
    self._exec(path, g)
    return g

  def _exec(self, path, g):
    with open(path, encoding='utf-8') as f:
      src = f.read()
    src = self._preprocess_loads(src, g)
    code = compile(src, path, 'exec')
    exec(code, g)  # pylint: disable=exec-used

  _LOAD_RE = re.compile(r'^load\((.*?)\)\s*$', re.M | re.S)

  def _preprocess_loads(self, src, g):
    """Executes load() statements and removes them from the source."""

    # load(...) calls may contain keyword aliases: handle with a full parse.
    out = []
    pos = 0
    for m in re.finditer(r'^load\(', src, re.M):
      end = _find_close(src, m.end() - 1)
      call = src[m.start():end + 1]
      out.append(src[pos:m.start()])
      self._do_load(call, g)
      out.append('\n' * call.count('\n'))
      pos = end + 1
    out.append(src[pos:])
    return ''.join(out)

  def _do_load(self, call, g):
    def _capture(label, *symbols, **aliases):
      return label, symbols, aliases
    label, symbols, aliases = eval(call.replace('load(', '_capture(', 1),  # pylint: disable=eval-used
                                   {'_capture': _capture})
    saved = self.cur_pkg
    mod = self.load_bzl(label)
    self.cur_pkg = saved
    for sym in symbols:
      g[sym] = self._lookup(mod, sym, label)
    for alias, sym in aliases.items():
      g[alias] = self._lookup(mod, sym, label)

  def _lookup(self, mod, sym, label):
    if mod is None:
      # External repository symbols.
      if sym in EXTERNAL_RULES or sym.endswith('_library') or \
          sym.endswith('_binary') or sym.endswith('_test'):
        return self.rule_fn(sym)
      if sym == 'selects':
        return Struct(config_setting_group=self.rule_fn('config_setting_group'),
                      with_or=lambda d, no_match_error=None: Sel([_expand_or(d)]))
      if sym == 'dicts':
        return Struct(add=_dicts_add)
      if sym == 'paths':
        return Struct(join=lambda *a: '/'.join(a),
                      basename=os.path.basename, dirname=os.path.dirname)
      if sym == 'CcInfo':
        return Struct
      return self.rule_fn(sym)
    if sym not in mod:
      raise KeyError('%s not found in %s' % (sym, label))
    return mod[sym]

  def load_package(self, pkg):
    if pkg in self.loaded_pkgs:
      return
    self.loaded_pkgs.add(pkg)
    path = os.path.join(self.root, pkg, 'BUILD.bazel')
    if not os.path.exists(path):
      raise IOError('no BUILD.bazel in package %r' % pkg)
    saved = self.cur_pkg
    self.cur_pkg = pkg
    self._exec(path, self._globals())
    self.cur_pkg = saved


def _dicts_add(*ds, **kw):
  out = {}
  for d in ds:
    out.update(d)
  out.update(kw)
  return out


def _expand_or(d):
  out = {}
  for k, v in d.items():
    if isinstance(k, tuple):
      for kk in k:
        out[kk] = v
    else:
      out[k] = v
  return out


def _find_close(src, open_idx):
  depth = 0
  i = open_idx
  in_str = None
  while i < len(src):
    c = src[i]
    if in_str:
      if c == '\\':
        i += 2
        continue
      if src.startswith(in_str, i):
        i += len(in_str)
        in_str = None
        continue
    else:
      if src.startswith('"""', i) or src.startswith("'''", i):
        in_str = src[i:i + 3]
        i += 3
        continue
      if c in '"\'':
        in_str = c
      elif c == '#':
        i = src.index('\n', i)
        continue
      elif c == '(':
        depth += 1
      elif c == ')':
        depth -= 1
        if depth == 0:
          return i
    i += 1
  raise ValueError('unbalanced parentheses')
