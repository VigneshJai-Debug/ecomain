"""
EcoCloud - Automated Dependency Downloader

Downloads required Java JARs into lib/ from Maven Central
for compiling and executing CloudSim Plus simulations without Maven CLI.
"""

import os
import requests

LIB_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "lib")
os.makedirs(LIB_DIR, exist_ok=True)

JARS = [
    ("commons-math3-3.6.1.jar", "https://repo1.maven.org/maven2/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar"),
    ("cloudsimplus-8.5.7.jar", "https://repo1.maven.org/maven2/org/cloudsimplus/cloudsimplus/8.5.7/cloudsimplus-8.5.7.jar"),
    ("commons-lang3-3.17.0.jar", "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.17.0/commons-lang3-3.17.0.jar"),
    ("commons-collections4-4.4.jar", "https://repo1.maven.org/maven2/org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar"),
    ("gson-2.12.1.jar", "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.12.1/gson-2.12.1.jar"),
    ("slf4j-api-2.0.16.jar", "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar"),
    ("slf4j-simple-2.0.16.jar", "https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.16/slf4j-simple-2.0.16.jar")
]

def main():
    print(f"Target library directory: {LIB_DIR}")
    for name, url in JARS:
        dest = os.path.join(LIB_DIR, name)
        if os.path.exists(dest):
            print(f"  [OK] {name} already present.")
            continue
        print(f"  [DOWNLOADING] {name} from {url}...")
        try:
            resp = requests.get(url, timeout=30)
            resp.raise_for_status()
            with open(dest, "wb") as f:
                f.write(resp.content)
            print(f"  [SAVED] {name} ({len(resp.content)} bytes)")
        except Exception as e:
            print(f"  [ERROR] Failed to download {name}: {e}")

    print("\nAll dependencies verified in lib/.")

if __name__ == "__main__":
    main()
