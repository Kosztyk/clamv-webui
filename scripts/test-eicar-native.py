#!/usr/bin/env python3
"""Run smoke-eicar.sh with real native ClamD + executable JAR; not a Docker isolation test."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]


def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--clamd', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--library-path', default='')
    parser.add_argument('--isolate', action='store_true', help='Use bubblewrap Linux containers with only the Compose-style mounts')
    parser.add_argument('--quarantine-failure', action='store_true', help='Force a quarantine failure and verify its public API warning')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='clamav-eicar-native-') as temporary:
        work = Path(temporary)
        for name in ['data/uploads', 'conf', 'signatures', 'tmp']:
            (work / name).mkdir(parents=True)
        eicar = b'X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'
        # Only the harmless EICAR signature is loaded. No live signature download is required.
        (work / 'signatures/eicar.hdb').write_text(hashlib.md5(eicar).hexdigest() + ':' + str(len(eicar)) + ':Eicar-Test-Signature\n')
        scanner_port, web_port = port(), port()
        config = work / 'clamd.conf'
        signatures = '/var/lib/clamav' if args.isolate else str(work / 'signatures')
        scratch = '/tmp' if args.isolate else str(work / 'tmp')
        config.write_text(f'Foreground yes\nDatabaseDirectory {signatures}\nTCPSocket {scanner_port}\nTCPAddr 127.0.0.1\nTemporaryDirectory {scratch}\nLogFile {scratch}/clamd.log\nPidFile {scratch}/clamd.pid\n')
        env = {k: v for k, v in os.environ.items() if not k.startswith(('CLAMAV_', 'SPRING_', 'SERVER_', 'MANAGEMENT_', 'API_V1_'))
               and k not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
        if args.library_path:
            env['LD_LIBRARY_PATH'] = args.library_path
        scanner_log = (work / 'clamd-process.log').open('wb')
        app_log = (work / 'application.log').open('wb')
        scanner_command = [str(args.clamd.resolve()), '--config-file=' + str(config)]
        jar_path = str(args.jar.resolve())
        web_prefix = []
        scanner_prefix = []
        if args.isolate:
            bwrap = shutil.which('bwrap')
            if not bwrap:
                parser.error('--isolate requires bubblewrap and working unprivileged user namespaces')
            common = [bwrap, '--unshare-user', '--unshare-pid', '--unshare-ipc', '--unshare-uts', '--die-with-parent',
                      '--ro-bind', '/usr', '/usr', '--symlink', 'usr/lib', '/lib', '--symlink', 'usr/lib64', '/lib64',
                      '--dir', '/etc', '--ro-bind', '/etc/passwd', '/etc/passwd', '--ro-bind', '/etc/group', '/etc/group',
                      '--ro-bind', '/etc/hosts', '/etc/hosts', '--proc', '/proc', '--dev', '/dev', '--tmpfs', '/tmp', '--dir', '/app']
            for java_config in Path('/etc').glob('java-*-openjdk'):
                common += ['--ro-bind', str(java_config), str(java_config)]
            engine = str(args.clamd.resolve().parents[1])
            scanner_prefix = common + ['--ro-bind', engine, '/opt/clamav', '--ro-bind', str(config), '/etc/clamd.conf',
                    '--ro-bind', str(work / 'signatures'), '/var/lib/clamav', '--dir', '/app/data',
                    '--ro-bind', str(work / 'data/uploads'), '/app/data/uploads', '--cap-drop', 'ALL']
            scanner_command = scanner_prefix + ['--', '/opt/clamav/sbin/clamd', '--config-file=/etc/clamd.conf']
            env['LD_LIBRARY_PATH'] = '/opt/clamav/lib'
            web_prefix = common + ['--bind', str(work / 'data'), '/app/data', '--bind', str(work / 'conf'), '/app/conf',
                    '--ro-bind', jar_path, '/app/clamav-web-client.jar', '--chdir', '/app', '--cap-drop', 'ALL', '--']
            jar_path = '/app/clamav-web-client.jar'
        scanner = subprocess.Popen(scanner_command, cwd=work, env=env, stdout=scanner_log, stderr=subprocess.STDOUT)
        app = None
        try:
            for _ in range(100):
                if scanner.poll() is not None:
                    raise RuntimeError('Native ClamD stopped: ' + (work / 'clamd-process.log').read_text()[-2000:])
                try:
                    with socket.create_connection(('127.0.0.1', scanner_port), timeout=1) as s:
                        s.sendall(b'zPING\0')
                        if b'PONG' in s.recv(256):
                            break
                except OSError:
                    pass
                time.sleep(0.1)
            else:
                raise RuntimeError('Native ClamD did not become ready')
            env.update(CLAMAV_HOST='127.0.0.1', CLAMAV_PORT=str(scanner_port), CLAMAV_UPLOAD_DIR='/app/data/uploads' if args.isolate else str(work / 'data/uploads'),
                       CLAMAV_UPLOAD_PATH_SCAN_ENABLED='true', CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM='false')
            if args.quarantine_failure:
                (work/'data/quarantine-blocked').write_text('Deliberately not a directory: synthetic test fixture')
                env.update(CLAMAV_QUARANTINE_ENABLED='true',CLAMAV_QUARANTINE_DIR='/app/data/quarantine-blocked' if args.isolate else str(work/'data/quarantine-blocked'))
            java = str(Path(shutil.which('java')).resolve())
            app = subprocess.Popen(web_prefix + [java, '-jar', jar_path, '--server.address=127.0.0.1', f'--server.port={web_port}',
                    '--spring.datasource.url=jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1',
                    '--clamav.metrics.clamd-initial-delay-ms=3600000'], cwd=work, env=env, stdout=app_log, stderr=subprocess.STDOUT)
            for _ in range(400):
                bootstrap = work / 'data/bootstrap-admin-password'
                if bootstrap.is_file() and bootstrap.read_text().strip():
                    break
                if app.poll() is not None:
                    raise RuntimeError('Application stopped before bootstrap')
                time.sleep(0.1)
            else:
                raise RuntimeError('Bootstrap password was not generated')
            smoke_env = dict(env, CLAMAV_SMOKE_URL=f'http://127.0.0.1:{web_port}', CLAMAV_SMOKE_PASSWORD=bootstrap.read_text().strip())
            if args.quarantine_failure:smoke_env['CLAMAV_SMOKE_EXPECT_QUARANTINE_FAILED']='true'
            subprocess.run(['bash', str(ROOT / 'scripts/smoke-eicar.sh')], cwd=ROOT, env=smoke_env, check=True, timeout=150)
            subprocess.run(['bash', str(ROOT / 'scripts/smoke-eicar.sh')], cwd=ROOT,
                           env=dict(smoke_env,CLAMAV_SMOKE_CLEAN='true',CLAMAV_SMOKE_FILENAME='f'*396+'.txt',
                                    CLAMAV_SMOKE_EXPECT_QUARANTINE_FAILED='false'),check=True,timeout=150)
            for _ in range(100):
                if not any(p.is_file() for p in (work/'data/uploads').iterdir()):break
                time.sleep(0.1)
            stored = list((work / 'data/uploads').iterdir())
            assert not any(p.is_file() for p in stored), 'Infected upload was not removed after its persisted verdict'
            assert (work / 'data/clamav-web-client.mv.db').is_file()
            print('PASS: native MULTISCAN with only the upload path configured and INSTREAM fallback disabled', flush=True)
            print('PASS: infected upload removed after persisted VIRUS_FOUND with '+('a recorded quarantine failure' if args.quarantine_failure else 'quarantine disabled'),flush=True)
            print('PASS: clean upload removed after persisted OK verdict',flush=True)
            if args.isolate:
                for private in ['clamav-web-client.mv.db', 'bootstrap-admin-password']:
                    with socket.create_connection(('127.0.0.1', scanner_port), timeout=5) as s:
                        s.sendall(('zSCAN /app/data/' + private + '\0').encode())
                        reply = s.recv(4096).decode()
                        assert 'ERROR' in reply and 'FOUND' not in reply and 'OK' not in reply, reply
                    print('PASS: isolated ClamD cannot access actual Web UI private file ' + private, flush=True)
                probe = subprocess.run(scanner_prefix + ['--', '/usr/bin/touch', '/app/data/uploads/.readonly-probe'], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                assert probe.returncode != 0 and b'Read-only file system' in probe.stderr, probe.stderr
                assert not (work / 'data/uploads/.readonly-probe').exists()
                print('PASS: isolated ClamD upload mount is enforced read-only by Linux', flush=True)
            print('NOTE: Docker-specific image/port behavior remains covered by Compose checks and the Docker CI workflow.', flush=True)
        except Exception:
            print((work / 'application.log').read_text()[-3000:])
            raise
        finally:
            for process in [app, scanner]:
                if process and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
            scanner_log.close()
            app_log.close()


if __name__ == '__main__':
    main()
