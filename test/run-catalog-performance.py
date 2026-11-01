"""Run the opt-in synthetic matrix on the existing CI emulator, preserving its JSON evidence."""
import json
from pathlib import Path
import subprocess

package = 'br.com.fabriciomatos.iptvplayer'
output = Path('app/build/aurora-ui')
output.mkdir(parents=True, exist_ok=True)
command = ['adb', 'shell', 'am', 'instrument', '-w', '-e', 'class',
           package + '.CatalogStartupPerformanceTest#measureMatrix',
           '-e', 'catalogBenchmark', 'true', '-e', 'catalogSizes',
           '10000,100000,300000,600000,1200000',
           package + '.test/androidx.test.runner.AndroidJUnitRunner']
try:
    result = subprocess.run(command, capture_output=True, text=True, timeout=10800)
    print(result.stdout, flush=True)
    print(result.stderr, flush=True)
    assert result.returncode == 0 and 'OK (1 test)' in result.stdout, 'Android catalog benchmark failed'
finally:
    evidence = subprocess.run(['adb', 'shell', 'run-as', package, 'cat', 'files/catalog-benchmark.json'],
                              capture_output=True, text=True, timeout=30)
    if evidence.returncode == 0:
        (output / 'catalog-benchmark.json').write_text(evidence.stdout, encoding='utf-8')
        print(evidence.stdout, flush=True)
    for name in ('cache-first-playback.json', 'gzip-download.json'):
        smoke = subprocess.run(['adb', 'shell', 'run-as', package, 'cat', 'files/' + name],
                               capture_output=True, text=True, timeout=30)
        if smoke.returncode == 0:
            (output / name).write_text(smoke.stdout, encoding='utf-8')
    logs = subprocess.run(['adb', 'logcat', '-d', '-s', 'AuroraStartupBenchmark:I', '*:S'],
                          capture_output=True, text=True, timeout=30)
    (output / 'catalog-benchmark.log').write_text(logs.stdout, encoding='utf-8')

rows = json.loads((output / 'catalog-benchmark.json').read_text(encoding='utf-8'))
assert [row['rows'] for row in rows] == [10000, 100000, 300000, 600000, 1200000]
assert all('one_url_changed' in row['elapsed_ms'] for row in rows), 'Benchmark matrix is incomplete'
