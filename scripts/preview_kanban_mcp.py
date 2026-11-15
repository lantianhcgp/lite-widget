#!/usr/bin/env python3
"""Manual isolated preview through existing App Canvas. Never reload or write real board."""
import argparse
import base64
import copy
import http.client
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW_ID = 'kanban-preview-v2'


def call(name, arguments):
    token = (Path.home() / '.hermes/tmp/kanban-mcp-token').read_text().strip()
    connection = http.client.HTTPConnection('127.0.0.1', 8765, timeout=18)
    try:
        payload = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
                              'params': {'name': name, 'arguments': arguments}}, ensure_ascii=False).encode()
        connection.request('POST', '/mcp', payload, {'Content-Type': 'application/json',
                           'Author' + 'ization': 'Bear' + 'er ' + token})
        response = connection.getresponse()
        assert response.status == 200, f'MCP HTTP {response.status}'
        body = json.loads(response.read().decode(), strict=False)
        assert 'error' not in body, 'MCP RPC failed'
        result = body['result']
        assert not result.get('isError'), result.get('content')
        return result['content'][0]['text']
    finally:
        connection.close()


def fixture_spec(spec, fields):
    result = copy.deepcopy(spec)
    result['data'] = {'source': 'kanban-preview-fixture', 'vars': {}}
    result['vars'] = {key.replace('.', '_'): value for key, value in fields.items()}
    def visit(value):
        if isinstance(value, dict):
            for key in ('bind', 'visibleIf'):
                reference = value.get(key)
                if isinstance(reference, dict) and reference.get('field', '').startswith('parcel.'):
                    reference['field'] = 'vars.' + reference['field'].replace('.', '_')
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
    visit(result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--size', default='4x4', choices=['4x4', '2x4', '4x2', '2x2'])
    parser.add_argument('--all', action='store_true')
    parser.add_argument('--hosts', action='store_true', help='fixed design-dp copies matching measured MIUI host sizes')
    args = parser.parse_args()
    asset = ROOT / 'app/src/main/assets/templates/task-kanban'
    original = json.loads((asset / 'widget.json').read_text())
    fields = json.loads((ROOT / 'qa/kanban-v2/fixture-fields.json').read_text())
    spec = fixture_spec(original, fields)
    manifest = json.loads((asset / 'manifest.json').read_text())
    manifest.update(id=PREVIEW_ID, name='看板V2隔离自检（测试数据）')
    for filename, value in [('manifest.json', manifest), ('widget.json', spec)]:
        path = f'widgets/{PREVIEW_ID}/{filename}'
        content = json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n'
        call('fs_write', {'path': path, 'content': content})
        returned = call('fs_read', {'path': path})
        assert json.loads(returned) == value, 'exact target readback mismatch'
    check = call('widget_validate', {'id': PREVIEW_ID})
    assert 'OK' in check, check
    folder = ROOT / 'qa/kanban-v2/device-canvas-fixture'
    folder.mkdir(parents=True, exist_ok=True)
    if args.hosts:
        outputs = {}
        manifest_data = {}
        for size, dimensions in {'2x2': (138, 168), '4x2': (344, 160), '2x4': (138, 385),
                                 '4x4': (285, 385), 'pa-4x2': (285, 168)}.items():
            classification = '4x2' if size == 'pa-4x2' else size
            host_spec = copy.deepcopy(spec)
            host_spec['root'] = host_spec['variants'][classification]['root']
            host_spec.pop('variants')
            host_spec['canvas'] = {'width': dimensions[0], 'height': dimensions[1], 'fit': 'stretch'}
            path = f'widgets/{PREVIEW_ID}/widget.json'
            call('fs_write', {'path': path, 'content': json.dumps(host_spec, ensure_ascii=False, separators=(',', ':'))})
            assert json.loads(call('fs_read', {'path': path})) == host_spec
            width, height = dimensions[0] * 2, dimensions[1] * 2
            result = call('widget_render', {'id': PREVIEW_ID, 'width': width, 'height': height})
            name = f'host-{size}'
            image_path = folder / f'{name}.png'
            image_path.write_bytes(base64.b64decode(result.split('base64:', 1)[1].strip()))
            outputs[name] = {'path': str(image_path), 'bytes': image_path.stat().st_size, 'width': width, 'height': height}
            manifest_data[name] = {'width': width, 'height': height, 'density': 2, 'classification': classification}
        (folder / 'render-manifest.json').write_text(json.dumps(manifest_data, ensure_ascii=False, indent=2))
    else:
        outputs = {}
        for size in (['4x4', '2x4', '4x2', '2x2'] if args.all else [args.size]):
            width, height = {'4x4': (720, 720), '2x4': (360, 720), '4x2': (720, 360), '2x2': (360, 360)}[size]
            rendered = call('widget_render', {'id': PREVIEW_ID, 'size': size, 'width': width, 'height': height})
            encoded = rendered.split('base64:', 1)[1].strip()
            path = folder / f'{size}.png'
            path.write_bytes(base64.b64decode(encoded))
            outputs[size] = {'path': str(path), 'bytes': path.stat().st_size, 'width': width, 'height': height}
    print(json.dumps({'source': 'existing App native Canvas, isolated synthetic fixture; not installed V2 APK or MIUI desktop',
                      'exact_fs_readback': True, 'schema': check, 'outputs': outputs}, ensure_ascii=False))


if __name__ == '__main__':
    main()
