"""
Low-level ctypes bridge to the native libaiorka shared library.

The library is resolved in this order:
  1. AIORKA_LIB_PATH environment variable (explicit override)
  2. Standard system search paths (LD_LIBRARY_PATH / DYLD_LIBRARY_PATH / PATH)
  3. The directory that contains this file (bundled distribution)
"""

import ctypes
import os
import sys
from pathlib import Path

# ── Library loading ────────────────────────────────────────────────────────────

def _lib_name() -> str:
    if sys.platform.startswith("linux"):
        return "libaiorka.so"
    elif sys.platform == "darwin":
        return "libaiorka.dylib"
    elif sys.platform == "win32":
        return "aiorka.dll"
    raise OSError(f"Unsupported platform: {sys.platform}")


def _load_library() -> ctypes.CDLL:
    explicit = os.environ.get("AIORKA_LIB_PATH")
    if explicit:
        return ctypes.CDLL(explicit)

    name = _lib_name()

    # Try system search path first
    try:
        return ctypes.CDLL(name)
    except OSError:
        pass

    # Fall back to the package directory (bundled build)
    bundled = Path(__file__).parent / name
    if bundled.exists():
        return ctypes.CDLL(str(bundled))

    raise OSError(
        f"Cannot find {name}. Set AIORKA_LIB_PATH to the library path, "
        "or place the library next to the aiorka package."
    )


_lib = _load_library()

# ── Function signatures ────────────────────────────────────────────────────────

_lib.aiorka_create.restype = ctypes.c_void_p
_lib.aiorka_create.argtypes = [ctypes.c_char_p]  # config_yaml or NULL

_lib.aiorka_execute.restype = ctypes.c_char_p
_lib.aiorka_execute.argtypes = [
    ctypes.c_void_p,  # handle
    ctypes.c_char_p,  # policy_id
    ctypes.c_char_p,  # messages_json
]

_lib.aiorka_set_key.restype = None
_lib.aiorka_set_key.argtypes = [
    ctypes.c_void_p,  # handle
    ctypes.c_char_p,  # env_var_name
    ctypes.c_char_p,  # key_value
]

_lib.aiorka_last_error.restype = ctypes.c_char_p
_lib.aiorka_last_error.argtypes = []

_lib.aiorka_free_string.restype = None
_lib.aiorka_free_string.argtypes = [ctypes.c_char_p]

_lib.aiorka_health.restype = ctypes.c_char_p
_lib.aiorka_health.argtypes = [ctypes.c_void_p]  # handle

_lib.aiorka_destroy.restype = None
_lib.aiorka_destroy.argtypes = [ctypes.c_void_p]  # handle

_lib.aiorka_version.restype = ctypes.c_char_p
_lib.aiorka_version.argtypes = []

# ── Thin wrappers (decode bytes → str, free library memory) ───────────────────

def lib_create(config_yaml: str | None) -> int:
    raw = config_yaml.encode() if config_yaml is not None else None
    handle = _lib.aiorka_create(raw)
    return handle  # 0 / None means failure


def lib_execute(handle: int, policy_id: str, messages_json: str) -> str | None:
    raw = _lib.aiorka_execute(
        handle,
        policy_id.encode(),
        messages_json.encode(),
    )
    if raw is None:
        return None
    result = raw.decode()
    _lib.aiorka_free_string(raw)
    return result


def lib_set_key(handle: int, env_var_name: str, key_value: str) -> None:
    _lib.aiorka_set_key(handle, env_var_name.encode(), key_value.encode())


def lib_last_error() -> str | None:
    raw = _lib.aiorka_last_error()
    if raw is None:
        return None
    msg = raw.decode()
    _lib.aiorka_free_string(raw)
    return msg


def lib_health(handle: int) -> str | None:
    raw = _lib.aiorka_health(handle)
    if raw is None:
        return None
    result = raw.decode()
    _lib.aiorka_free_string(raw)
    return result


def lib_destroy(handle: int) -> None:
    _lib.aiorka_destroy(handle)


def lib_version() -> str:
    raw = _lib.aiorka_version()
    result = raw.decode()
    _lib.aiorka_free_string(raw)
    return result
