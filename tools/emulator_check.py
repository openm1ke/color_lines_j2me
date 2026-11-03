"""Run visible, isolated FreeJ2ME checks. Requires a graphical session."""
import os
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
cp = os.pathsep.join([str(ROOT / 'build/tests'), str(ROOT / 'tools/freej2me.jar')])
# Separate launches: this FreeJ2ME build does not resize GameCanvas buffers live.
for width, height in [(240, 320), (176, 208), (320, 240)]:
    runtime = ROOT / 'build/emulator-check' / f'runtime-{width}x{height}'
    config = runtime / 'config/Lines/game.conf'
    config.parent.mkdir(parents=True, exist_ok=True)
    # FreeJ2ME restores per-game dimensions after reading command-line arguments.
    config.write_text(f"""backlightcolor:Disabled
compatfantasyzonefix:off
compatignorevolumechanges:off
compatimmediaterepaints:off
compatoverrideplatchecks:on
compatsiemensfriendlydrawing:off
compattranstooriginonreset:off
dojaversion:200
fontoffset:0
fps:0
fpshack:Disabled
phone:Standard
rotate:0
scrheight:{height}
scrwidth:{width}
sound:on
soundfont:Default
spdhackm3ghalfres:off
spdhacknoalpha:off
textfont:Default
""")
    (runtime / 'freej2me_system').mkdir(exist_ok=True)
    print(f'FreeJ2ME visible check: {width}x{height}', flush=True)
    subprocess.run([java, '-Dfile.encoding=ISO_8859_1', f'-Dlines.store=lines-ui-{width}x{height}', f'-Dlines.project={ROOT}',
                    '-cp', cp, 'EmulatorCheck', str(width), str(height)], check=True, timeout=60, cwd=runtime)
print('ALL VISIBLE EMULATOR CHECKS PASSED', flush=True)
