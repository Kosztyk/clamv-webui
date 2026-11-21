#!/usr/bin/env python3
"""Fail closed on API v1 OpenAPI/controller/Go CLI structural drift.

Live MockMvc response shape tests complement (not replace) this source inventory;
Spectral and oasdiff are separately mandatory in CI.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import sys

import yaml

ROOT = Path(__file__).resolve().parents[1]
HTTP = {'Get': 'get', 'Post': 'post', 'Put': 'put', 'Patch': 'patch', 'Delete': 'delete'}


def load_spec(path: Path) -> dict:
    doc = yaml.safe_load(path.read_text(encoding='utf-8'))
    if not isinstance(doc, dict) or doc.get('openapi') != '3.0.3':
        raise ValueError(f'{path}: expected OpenAPI 3.0.3 root')
    if not doc.get('paths') or not doc.get('components', {}).get('schemas'):
        raise ValueError(f'{path}: absent paths/schemas')
    return doc


def controller_routes(root: Path) -> set[tuple[str, str]]:
    routes = set()
    for name in ('ApiV1Controller.java', 'ApiTokenAdminController.java'):
        p = root / 'src/main/java/info/trizub/clamav/webclient/api/v1' / name
        source = p.read_text()
        base = re.search(r'@RequestMapping\("([^"]+)"\)', source)
        if not base or not base.group(1).startswith('/api/v1'):
            raise ValueError(f'{p}: missing API v1 controller mapping')
        # No regular-expression parsing of Java methods; only annotation path literals.
        # The runtime contract test introspects actual Spring HandlerMappings.
        for kind, attrs in re.findall(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?', source):
            match = re.search(r'"(/[^"]*)"', attrs)
            suffix = match.group(1) if match else ''
            full = (base.group(1) + suffix).removeprefix('/api/v1')
            routes.add((HTTP[kind], full or '/'))
    return routes


def cli_routes(root: Path) -> set[tuple[str, str]]:
    routes = set()
    for p in (root/'cli/internal/cli').glob('*.go'):
        source = p.read_text()
        # Find HTTP calls with a literal verb (or http.MethodX) and path string.
        pattern = r'\.request\(\s*[^,]+,\s*(?:"(?P<literal>GET|POST|PUT|PATCH|DELETE)"|http\.Method(?P<symbol>Get|Post|Put|Patch|Delete)),\s*"(?P<path>/[^"\n]*)"'
        for m in re.finditer(pattern, source):
            method = m.group('literal').lower() if m.group('literal') else HTTP[m.group('symbol')]
            path = m.group('path')
            if path.endswith('/'):
                # /jobs/+id and /jobs/+id+/progress
                tail = source[m.end():m.end()+85]
                path = path.rstrip('/')+'/{id}'
                if re.match(r'\s*\+\s*id\s*\+\s*"/progress"', tail):
                    path += '/progress'
            routes.add((method, path))
    return routes


def validate(root: Path = ROOT, spec_path: Path | None = None) -> list[str]:
    doc = load_spec(spec_path or root/'src/main/resources/openapi/clamav-v1.yaml')
    paths = doc['paths']
    specified = {(method, path) for path, methods in paths.items() for method in methods if method in HTTP.values()}
    actual = controller_routes(root)
    issues = []
    for method, path in sorted(specified - actual):
        issues.append(f'OpenAPI operation without controller: {method.upper()} {path}')
    for method, path in sorted(actual - specified):
        issues.append(f'Controller endpoint missing from OpenAPI: {method.upper()} {path}')
    for method, path in sorted(cli_routes(root) - specified):
        issues.append(f'Go CLI endpoint missing from OpenAPI/controller: {method.upper()} {path}')
    for method, path in sorted(specified):
        responses = paths[path][method].get('responses', {})
        if not responses or not any(code in responses for code in ('200','201','202','204')):
            issues.append(f'{method.upper()} {path} has no success response')
        if path in {'/scans/uploads', '/scans/paths', '/jobs', '/jobs/{id}', '/jobs/{id}/progress', '/tokens'}:
            for code, content in responses.items():
                if str(code) in {'200', '201', '202'} and 'application/json' not in content.get('content', {}):
                    issues.append(f'{method.upper()} {path} {code} does not declare JSON schema')
    for endpoint, method, response, protected in (
            ('/auth','get','200',{'authenticated','principal','authentication'}),
            ('/csrf','get','200',{'token','headerName','parameterName'}),
            ('/metrics/clamd','get','200',{'endpointId','ok','threads','queue','pools','memory','checkedAt'})):
        payload = paths[endpoint][method]['responses'][response]['content']['application/json']['schema']
        if not protected.issubset(set(payload.get('required',[])) & set(payload.get('properties',{}))):
            issues.append(f'{endpoint} response schema lost required fields')
    schemas = doc['components']['schemas']
    for name, required in {
            'Job': {'id','type','status','submittedAt','signatures','quarantineStatus'},
            'JobPage': {'items','page','size','totalItems','totalPages'},
            'Error': {'error','message','timestamp'},
            'UploadSubmission': {'created','jobIds'},
            'PathSubmission': {'jobId'},
            'JobProgress': {'jobId','status','completed','verdict','updatedAt'},
            'Token': {'id','label','prefix','scopes','endpointIds','pathRoots','createdAt'},
            'IssuedToken': {'token','secret'},
    }.items():
        current = schemas.get(name) or {}
        if not required.issubset(set(current.get('required', [])) & set(current.get('properties', {}))):
            issues.append(f'{name} schema is missing protected required fields: {sorted(required - set(current.get("required", [])))}')
    return issues


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('--spec', type=Path)
    params = args.parse_args()
    issues = validate(ROOT, params.spec)
    if issues:
        for msg in issues:
            print('FAIL:', msg, file=sys.stderr)
        raise SystemExit(1)
    print('PASS: OpenAPI operations, controller mappings, CLI routes, success/error schemas and protected response fields agree')

if __name__ == '__main__': main()
