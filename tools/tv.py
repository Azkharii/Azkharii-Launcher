#!/usr/bin/env python3
"""
tools/tv.py — Television ADB inspection, screenshot, and safe operation helper.
Adheres strictly to MASTER_PROMPT Annex B, G, and user safety guidelines.
"""

import sys
import os
import argparse
import subprocess
import time

def run_adb(args, serial=None, timeout=10):
    cmd = ["adb"]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(args)
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout)
        return res.returncode, res.stdout.strip(), res.stderr.strip()
    except subprocess.TimeoutExpired:
        return -1, "", "Command timed out"
    except Exception as e:
        return -1, "", str(e)

def get_devices():
    code, stdout, _ = run_adb(["devices"])
    if code != 0:
        return []
    devices = []
    for line in stdout.splitlines()[1:]:
        parts = line.strip().split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices

def inspect_device(serial=None):
    print(f"=== TV Device Inspection ({serial or 'default'}) ===")
    props = [
        ("Model", ["getprop", "ro.product.model"]),
        ("Manufacturer", ["getprop", "ro.product.manufacturer"]),
        ("Android Version", ["getprop", "ro.build.version.release"]),
        ("SDK Level", ["getprop", "ro.build.version.sdk"]),
        ("Fingerprint", ["getprop", "ro.build.fingerprint"]),
        ("Display Size", ["wm", "size"]),
        ("Display Density", ["wm", "density"]),
    ]
    for label, cmd in props:
        code, out, err = run_adb(["shell"] + cmd, serial)
        if code == 0 and out:
            print(f"  {label:20}: {out}")
        else:
            print(f"  {label:20}: [unavailable]")

    # Check HOME activity
    print("\n=== HOME Claiming Activities ===")
    code, out, _ = run_adb(["shell", "cmd", "package", "query-activities",
                              "-a", "android.intent.action.MAIN",
                              "-c", "android.intent.category.HOME"], serial)
    if code == 0 and out:
        print(out)
    else:
        print("  [unavailable or no responses]")

def capture_screenshot(output_path, key_to_inject=None, serial=None):
    if key_to_inject:
        print(f"Injecting key: {key_to_inject}")
        run_adb(["shell", "input", "keyevent", key_to_inject], serial)
        time.sleep(0.5)

    remote_tmp = "/data/local/tmp/tv_screen.png"
    print(f"Capturing screenshot to {output_path}...")
    code, _, err = run_adb(["shell", "screencap", "-p", remote_tmp], serial)
    if code != 0:
        print(f"Error capturing screenshot on TV: {err}")
        return False

    code, _, err = run_adb(["pull", remote_tmp, output_path], serial)
    run_adb(["shell", "rm", "-f", remote_tmp], serial)

    if code == 0 and os.path.exists(output_path) and os.path.getsize(output_path) > 0:
        print(f"Screenshot successfully saved to {output_path} ({os.path.getsize(output_path)} bytes)")
        return True
    else:
        print(f"Failed to pull screenshot or file is empty.")
        return False

def main():
    parser = argparse.ArgumentParser(description="TV inspection and safe operations helper")
    parser.add_argument("-s", "--serial", help="ADB target device serial")
    parser.add_argument("--inspect", action="store_true", help="Inspect device hardware and properties")
    parser.add_argument("--screenshot", metavar="FILE", help="Capture screenshot to target file")
    parser.add_argument("keys", nargs="*", help="Optional keys to inject before screenshot (e.g. DPAD_RIGHT)")

    args = parser.parse_args()

    devices = get_devices()
    target = args.serial or (devices[0] if devices else None)

    if not target:
        print("No active ADB devices connected.")
        if args.inspect:
            print("Device inspection marked as unavailable.")
        sys.exit(0)

    if args.inspect:
        inspect_device(target)

    if args.screenshot:
        key = args.keys[0] if args.keys else None
        success = capture_screenshot(args.screenshot, key_to_inject=key, serial=target)
        sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
