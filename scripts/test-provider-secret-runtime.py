#!/usr/bin/env python3
"""Exercise provider-secret protection over real HTTP with local TLS-failing sentinels."""
import argparse
import importlib.util
import json
from pathlib import Path
import socket
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('configuration_smoke', ROOT / 'tools/verify-configuration-center.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class Sentinel:
    """Accept and close TCP, without presenting a TLS certificate or accepting a bind."""
    def __init__(self):
        self.socket = socket.socket()
        self.socket.bind(('127.0.0.1', 0))
        self.socket.listen()
        self.socket.settimeout(0.1)
        self.port = self.socket.getsockname()[1]
        self.hits = 0
        self.done = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def run(self):
        while not self.done.is_set():
            try:
                connection, _ = self.socket.accept()
                self.hits += 1
                connection.close()
            except socket.timeout:
                pass
            except OSError:
                break

    def close(self):
        self.done.set()
        self.socket.close()
        self.thread.join(timeout=2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/clamav-web-client-2.1.0.jar')
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error('Build with mvn verify first, or supply --jar')
    checks = 0

    def check(condition, message):
        nonlocal checks
        if not condition:
            raise AssertionError(message)
        checks += 1
        print(f'PASS {checks}: {message}', flush=True)

    sentinels = {provider: [Sentinel(), Sentinel()] for provider in ('ldap', 'oidc')}
    secrets = {'ldap': 'synthetic-runtime-ldap-secret', 'oidc': 'synthetic-runtime-oidc-secret'}

    def key(provider):
        return f'clamav.identity.{provider}.' + ('manager-password' if provider == 'ldap' else 'client-secret')

    def target(provider):
        return f'clamav.identity.{provider}.' + ('url' if provider == 'ldap' else 'issuer')

    def url(provider, sentinel):
        return ('ldaps' if provider == 'ldap' else 'https') + f'://127.0.0.1:{sentinel.port}'

    def field(view, name):
        return next(item for item in view['fields'] if item['definition']['key'] == name)

    expected = {}
    try:
        with tempfile.TemporaryDirectory(prefix='clamav-provider-secret-runtime-') as temporary:
            folder = Path(temporary)
            app = smoke.Application(jar, folder)
            try:
                app.wait('/setup/')
                _, view = app.api('view', {})
                changes = {'clamav.service.host': '127.0.0.1', 'clamav.service.port': '9',
                    'clamav.identity.ldap.base-dn': 'dc=example',
                    'clamav.identity.ldap.manager-dn': 'cn=reader,dc=example',
                    'clamav.identity.ldap.group-search-base': 'ou=groups',
                    'clamav.identity.ldap.groups.viewer': 'readers',
                    'clamav.identity.oidc.groups.viewer': 'readers',
                    'clamav.identity.oidc.client-id': 'synthetic-client'}
                for provider in sentinels:
                    changes.update({f'clamav.identity.{provider}.enabled': 'false',
                                    target(provider): url(provider, sentinels[provider][0]), key(provider): secrets[provider]})
                status, reviewed = app.api('review', {'version': view['version'], 'changes': changes})
                check(status == 200, 'Initial provider configuration accepts explicitly supplied credentials')
                check(app.api('apply', {'reviewId': reviewed['reviewId'], 'acknowledgeDatabase': True})[0] == 200,
                      'Initial configuration saves before application startup')

                def cases(mode):
                    call = app.api if mode == 'recovery' else app.admin
                    for provider in sentinels:
                        _, view = call('view', {} if mode == 'recovery' else None)
                        version = view['version']
                        current = field(view, target(provider))['value']
                        old, changed = sorted(sentinels[provider], key=lambda item: url(provider, item) != current)
                        alternate = url(provider, changed)
                        draft = {'version': version, 'section': provider, 'changes': {key(provider): ''}}
                        old_hits = old.hits
                        status, result = call('test', draft)
                        check(status == 400 and 'Re-enter' not in result.get('message', '') and old.hits > old_hits,
                              f'{mode} {provider}: unchanged blank credential reaches the original TLS sentinel')
                        # The sentinel deliberately fails TLS. A live-provider success is tested separately by operators.
                        draft['changes'][target(provider)] = alternate
                        changed_hits = changed.hits
                        for action in ('test', 'review'):
                            status, result = call(action, draft)
                            check(status == 400 and 'Re-enter' in result.get('message', ''),
                                  f'{mode} {provider}: changed destination with blank credential rejects {action}')
                            check(all(secret not in json.dumps(result) for secret in secrets.values()),
                                  f'{mode} {provider}: rejected {action} does not echo credentials')
                        check(changed.hits == changed_hits,
                              f'{mode} {provider}: rejected drafts make no connection to the changed destination')
                        _, unchanged = call('view', {} if mode == 'recovery' else None)
                        check(unchanged['version'] == version and field(unchanged, target(provider))['value'] == current,
                              f'{mode} {provider}: rejection preserves saved configuration')
                        draft['changes'].pop(target(provider))
                        status, reviewed = call('review', draft)
                        check(status == 200, f'{mode} {provider}: unchanged blank credential can be reviewed')
                        apply = {'reviewId': reviewed['reviewId'], 'acknowledgeDatabase': True,
                                 'verificationUsername': 'admin', 'verificationPassword': smoke.ADMIN_PASSWORD}
                        check(call('apply', apply)[0] == 200, f'{mode} {provider}: unchanged blank credential can be saved')
                        _, view = call('view', {} if mode == 'recovery' else None)
                        draft.update(version=view['version'], changes={target(provider): alternate, key(provider): secrets[provider]})
                        status, reviewed = call('review', draft)
                        check(status == 200 and secrets[provider] not in json.dumps(reviewed),
                              f'{mode} {provider}: explicit re-entry of the same credential permits a masked review')
                        apply['reviewId'] = reviewed['reviewId']
                        check(call('apply', apply)[0] == 200, f'{mode} {provider}: explicit re-entry permits destination save')
                        expected[provider] = alternate

                cases('recovery')
                check(app.api('start', {})[0] == 200, 'Configured application starts with disabled external providers')
                app.wait('/login')
                app.login()
                cases('admin')
                stored = (folder / 'conf/configuration-center.properties').read_text()
                check('cc1' in stored and all(secret not in stored for secret in secrets.values()),
                      'Saved credentials remain encrypted at rest')
            finally:
                app.close()
            app = smoke.Application(jar, folder)
            try:
                app.wait('/login')
                app.login()
                _, view = app.admin('view')
                for provider in sentinels:
                    check(field(view, target(provider))['value'] == expected[provider] and field(view, key(provider))['configured']
                          and not field(view, key(provider))['value'],
                          f'{provider}: destination and masked credential survive restart')
            finally:
                app.close()
    finally:
        for pair in sentinels.values():
            for sentinel in pair:
                sentinel.close()
    print(json.dumps({'checks': checks, 'result': 'passed', 'live_provider_validation': False}))


if __name__ == '__main__':
    main()
