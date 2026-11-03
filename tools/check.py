"""Validate distribution metadata, CLDC bytecode and game regressions."""
from pathlib import Path
import os, shutil, struct, subprocess, zipfile, wave
ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
props = dict(line.strip().split('=', 1) for line in Path('local.properties').read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))
jar = Path('dist') / (props['suite.name'] + '.jar')
jad = dict(line.split(': ', 1) for line in jar.with_suffix('.jad').read_text().splitlines() if ': ' in line)
assert int(jad['MIDlet-Jar-Size']) == jar.stat().st_size
assert jad['MicroEdition-Profile'] == 'MIDP-' + props['midp.profile']
with zipfile.ZipFile(jar) as z:
    manifest = z.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '')
    assert 'MicroEdition-Profile: MIDP-' + props['midp.profile'] in manifest
    assert 'img/lines2.png' in z.namelist()
    assert 'lines/LinesMidlet.class' in z.namelist()
    assert 'music/minigames.mid' in z.namelist() and z.read('music/minigames.mid').startswith(b'MThd')
    assert 'CC0' in z.read('music/LICENSE.txt').decode()
    assert 'CC0' in z.read('sfx/LICENSE.txt').decode()
    for n in range(1, 4):
        pcm = z.read(f'sfx/pop{n}.wav')
        assert pcm == Path(f'res/sfx/pop{n}.wav').read_bytes()
        # This FreeJ2ME version assumes a 44-byte WAV header and cannot skip LIST chunks.
        assert pcm[:4] == b'RIFF' and pcm[12:16] == b'fmt ' and pcm[36:40] == b'data'
        assert struct.unpack_from('<I', pcm, 16)[0] == 16
        assert struct.unpack_from('<I', pcm, 40)[0] == len(pcm) - 44
        with wave.open(f'res/sfx/pop{n}.wav') as sound:
            assert sound.getnchannels() == 1 and sound.getsampwidth() == 2 and sound.getframerate() == 16000
            assert 0.02 < sound.getnframes() / sound.getframerate() < 0.3
    assert 'lines/GameStore.class' in z.namelist() and 'lines/Soundtrack.class' in z.namelist()
    assert not any(n.startswith(('java/', 'javax/')) for n in z.namelist())
    for n in z.namelist():
        if n.endswith('.class'):
            data=z.read(n)
            assert struct.unpack('>H', data[6:8])[0] == 45, (n, 'not Java 1.1 bytecode')
            if n == 'lines/LinesCanvas.class':
                assert b'StackMap' in data, (n, 'missing CLDC preverification')
print('PASS: JAR/JAD metadata, resources, API isolation and CLDC preverification', flush=True)
def jdk_tool(name):
    return str(Path(os.environ['JAVA_HOME']) / 'bin' / name) if os.environ.get('JAVA_HOME') else name
out=Path('build/tests');out.mkdir(parents=True, exist_ok=True)
cp=os.pathsep.join(['build/classes','tools/freej2me.jar'])
subprocess.run([jdk_tool('javac'),'-encoding','UTF-8','-cp',cp,'-d',str(out),*[str(p) for p in sorted(Path('tests').glob('*.java'))]],check=True)
result=subprocess.run([jdk_tool('java'),'-Dfile.encoding=ISO_8859_1','-Dlines.store=lines-automated-check','-Djava.awt.headless=true','-cp',os.pathsep.join([str(out),cp]),'GameplayCheck'],check=False,timeout=60,capture_output=True,text=True)
print(result.stdout, end='')
print(result.stderr, end='')
result.check_returncode()
assert 'ALL GAMEPLAY CHECKS PASSED' in result.stdout, 'Game checks exited before completion'

features=subprocess.run([jdk_tool('java'),'-Dfile.encoding=ISO_8859_1','-Dlines.store=lines-feature-check','-Djava.awt.headless=true','-cp',os.pathsep.join([str(out),cp]),'FeatureCheck'],check=False,timeout=60,capture_output=True,text=True)
print(features.stdout,end='');print(features.stderr,end='');features.check_returncode()
assert 'ALL FEATURE CHECKS PASSED' in features.stdout
