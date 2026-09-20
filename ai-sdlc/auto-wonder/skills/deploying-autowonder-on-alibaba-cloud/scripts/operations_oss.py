"""Private OSS transport for operations snapshots; requires the ossutil v2 API.

Only the Alibaba CLI profile named auto-wonder supplies credentials. Each child
process receives a private, short-lived config, never credentials in arguments.
Bucket versioning MUST never have been enabled: OSS ignores forbid-overwrite
on enabled or suspended versioning buckets. History is stored by the caller as
immutable snapshot objects instead.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from operation_metrics import measure


class StoreError(RuntimeError):
    """Sanitized transport error; remote output is deliberately not retained."""


class ConflictError(StoreError):
    """An atomic create encountered an existing object."""


def _run(command, **kwargs):
    try:
        return subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=180, check=False, **kwargs)
    except (OSError, subprocess.SubprocessError):
        raise StoreError('OSS CLI invocation failed') from None


def _json(data):
    try:
        # ossutil appends a timing line after the JSON document.
        text = data.decode('utf-8').lstrip()
        value, end = json.JSONDecoder().raw_decode(text)
        rest = text[end:]
        if rest.strip() and not re.fullmatch(r'\s*\d+(?:\.\d+)?\(s\) elapsed\s*', rest):
            raise ValueError()
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (ValueError, UnicodeError):
        raise StoreError('OSS returned an invalid response') from None


def _error_code(result):
    output = (result.stderr + b'\n' + result.stdout).decode('utf-8', 'replace')
    # Match a structured error field, never an arbitrary substring in a message.
    match = re.search(r'(?:Error Code:\s*|ErrorCode[=:]\s*|<Code>)([A-Za-z][A-Za-z0-9]+)(?=\s|<|,|$|\.(?:\s|$))', output)
    if match:
        return match.group(1)
    try:
        return json.loads(output.strip()).get('Code')
    except (ValueError, AttributeError):
        return None


def _protect_directory(directory):
    if os.name != 'nt':
        os.chmod(directory, 0o700)
        return
    result = _run(['whoami', '/user', '/fo', 'csv', '/nh'])
    sid = re.search(rb'S-1-[0-9-]+', result.stdout)
    if result.returncode or sid is None:
        raise StoreError('Cannot identify the current Windows account for private storage')
    result = _run(['icacls', directory, '/inheritance:r', '/grant:r',
                   '*' + sid.group().decode('ascii') + ':(OI)(CI)F'])
    if result.returncode:
        raise StoreError('Cannot secure the temporary operations directory')


def _private_file(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)


class OssStore:
    def __init__(self, region, account_id=None):
        if not isinstance(region, str) or not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)+', region):
            raise StoreError('A valid OSS region is required')
        self.region = region
        self.expected_account = str(account_id) if account_id else None
        self.account_id = None
        self._cli = None

    def identity(self):
        if self.account_id is None:
            result = _run(['aliyun', 'sts', 'GetCallerIdentity', '--profile', 'auto-wonder', '--region', self.region])
            if result.returncode:
                raise StoreError('The auto-wonder account identity is unavailable')
            account = _json(result.stdout).get('AccountId')
            if not account or not str(account).isdigit():
                raise StoreError('The auto-wonder account identity is invalid')
            if self.expected_account is not None and self.expected_account != str(account):
                raise StoreError('The auto-wonder account does not match the operations binding')
            self.account_id = str(account)
        return self.account_id

    def _command(self):
        if self._cli is None:
            for candidate in (['ossutil'], ['aliyun', 'ossutil']):
                try:
                    result = _run(candidate + ['api', 'put-object', '--help'])
                except StoreError:
                    continue
                if not result.returncode and all(flag in result.stdout for flag in
                        (b'--forbid-overwrite', b'--object-acl', b'--server-side-encryption', b'--ignore-env-var')):
                    self._cli = candidate
                    break
            if self._cli is None:
                raise StoreError('Operations storage requires ossutil v2 (standalone or aliyun ossutil); legacy is unsafe')
        return self._cli

    def _credentials(self):
        source = Path(os.environ.get('ALIBABA_CLOUD_CLI_CONFIG_FILE', str(Path.home() / '.aliyun' / 'config.json')))
        try:
            profiles = json.loads(source.read_text())['profiles']
            profile = next(p for p in profiles if p.get('name') == 'auto-wonder')
            values = [profile['access_key_id'], profile['access_key_secret'], profile.get('sts_token', '')]
            if not values[0] or not values[1] or any(not isinstance(v, str) or '\n' in v or '\r' in v for v in values):
                raise ValueError()
        except (OSError, ValueError, KeyError, StopIteration, TypeError):
            raise StoreError('The auto-wonder profile needs usable AK/STS credentials') from None
        key, secret, token = values
        return ('[Credentials]\naccessKeyID=' + key + '\naccessKeySecret=' + secret + '\n' +
                ('stsToken=' + token + '\n' if token else '') +
                'mode=' + ('StsToken' if token else 'AK') + '\n').encode()

    @measure('oss_transport')
    def _execute(self, args, *, body=None, download=False, missing=False, conflict=False):
        command = self._command()
        self.identity()
        credentials = self._credentials()
        with tempfile.TemporaryDirectory(prefix='aw-ops-') as directory:
            _protect_directory(directory)
            root = Path(directory)
            config = root / 'credentials'
            _private_file(config, credentials)
            args = list(args)
            if body is not None:
                data = root / 'body'
                _private_file(data, body)
                args += ['--body', 'file://' + str(data)]
            target = root / 'download'
            if download:
                _private_file(target, b'')
                args += [str(target), '--force']
            env = {k: v for k, v in os.environ.items() if not k.startswith(('OSS_', 'ALIBABA_CLOUD_ACCESS_KEY', 'ALICLOUD_ACCESS_KEY', 'ALICLOUD_SECRET_KEY'))}
            result = _run(command + args + ['--config-file', str(config), '--ignore-env-var',
                '--region', self.region, '--endpoint', 'https://oss-' + self.region + '.aliyuncs.com',
                '--loglevel', 'off', '--quiet'], env=env, cwd=directory)
            if result.returncode:
                code = _error_code(result)
                if missing and code in ('NoSuchKey', 'NoSuchBucket'):
                    return None
                if conflict and code in ('FileAlreadyExists', 'ObjectAlreadyExists', 'PreconditionFailed'):
                    raise ConflictError('OSS conditional creation conflicted')
                raise StoreError('OSS operation failed; check account permissions and connectivity')
            if download:
                try:
                    return target.read_bytes()
                except OSError:
                    raise StoreError('OSS download produced no readable object') from None
            return result.stdout

    def _api(self, action, bucket=None, *args, missing=False):
        command = ['api', action]
        if bucket:
            command += ['--bucket', bucket]
        output = self._execute(command + list(args) + ['--output-format', 'json'], missing=missing)
        return None if output is None else _json(output)

    def _unversioned(self, bucket):
        config = self._api('get-bucket-versioning', bucket)
        if config.get('Status') not in (None, ''):
            raise StoreError('Atomic operations require a bucket whose versioning was never enabled')

    def get(self, bucket, key):
        return self._execute(['cp', 'oss://' + bucket + '/' + key], download=True, missing=True)

    def head(self, bucket, key):
        """Return length without downloading the body; only exact absence is None."""
        response = self._api('head-object', bucket, '--key', key, missing=True)
        if response is None:
            return None
        # ossutil v2 exposes HTTP headers as arrays, unlike body-based API JSON.
        headers = response.get('Header')
        if not isinstance(headers, dict):
            raise StoreError('OSS object length is missing or invalid')
        lengths = [value for name, value in headers.items() if name.lower() == 'content-length']
        if len(lengths) != 1 or not isinstance(lengths[0], list) or len(lengths[0]) != 1:
            raise StoreError('OSS object length is missing or invalid')
        value = lengths[0][0]
        if not isinstance(value, str) or not re.fullmatch(r'[0-9]+', value):
            raise StoreError('OSS object length is missing or invalid')
        try:
            return int(value)
        except ValueError:
            raise StoreError('OSS object length is missing or invalid') from None

    def put(self, bucket, key, data, create_only=False):
        if create_only:
            self._unversioned(bucket)
        args = ['api', 'put-object', '--bucket', bucket, '--key', key,
                '--object-acl', 'private', '--server-side-encryption', 'AES256']
        if create_only:
            args += ['--forbid-overwrite']
        self._execute(args, body=data, conflict=create_only)

    def delete(self, bucket, key):
        self._execute(['api', 'delete-object', '--bucket', bucket, '--key', key])

    def validate_teardown_bucket(self, binding):
        """Never select a bucket from a caller-supplied name alone."""
        deployment_id = binding['deploymentId']
        bucket = binding['bucket']
        if (binding.get('accountUid') != self.identity() or binding.get('region') != self.region
                or bucket != self._bucket(deployment_id)):
            raise StoreError('Operations teardown binding does not match this deployment')
        expected = {'schemaVersion': 1, 'accountUid': self.identity(),
                    'region': self.region, 'deploymentId': deployment_id}
        identity = self.get(bucket, 'identity.json')
        if identity is None or _json(identity) != expected:
            raise StoreError('Operations teardown requires the exact bucket identity')
        self._unversioned(bucket)

    def destroy_operations_bucket(self, binding, lock):
        """Delete only enumerated keys in the verified dedicated, locked bucket."""
        self.validate_teardown_bucket(binding)
        bucket = binding['bucket']
        if self.get(bucket, 'write-lock.json') != lock:
            raise StoreError('Operations teardown lock is not owned by this attempt')
        keys, marker, seen = [], '', set()
        while True:
            args = ['--marker', marker] if marker else []
            page = self._api('list-objects', bucket, *args)
            contents = page.get('Contents') or []
            if isinstance(contents, dict):
                contents = [contents]
            if not isinstance(contents, list):
                raise StoreError('Operations object listing is invalid')
            for item in contents:
                key = item.get('Key') if isinstance(item, dict) else None
                if (not isinstance(key, str) or key in keys or
                        not (key in ('identity.json', 'current.json', 'write-lock.json') or
                             re.fullmatch(r'(deploy|upgrade)/(objects|records)/[0-9a-f]{64}', key))):
                    raise StoreError('Operations teardown found an unexpected object')
                keys.append(key)
            if page.get('IsTruncated') not in (True, 'true'):
                break
            marker = page.get('NextMarker')
            if not isinstance(marker, str) or not marker or marker in seen:
                raise StoreError('Operations object listing pagination is invalid')
            seen.add(marker)
        controls = ('current.json', 'identity.json', 'write-lock.json')
        for key in [key for key in keys if key not in controls] + list(controls):
            if self.get(bucket, 'write-lock.json') != lock:
                raise StoreError('Operations teardown lost its lock')
            self.delete(bucket, key)
        self._api('delete-bucket', bucket)
        if self._api('get-bucket-info', bucket, missing=True) is not None:
            raise StoreError('Operations bucket deletion is not verified')

    def _bucket(self, deployment_id):
        slug = re.sub('[^a-z0-9-]+', '-', deployment_id.lower()).strip('-')[:42].rstrip('-') or 'deployment'
        digest = hashlib.sha256((self.identity() + '\n' + self.region + '\n' + deployment_id).encode()).hexdigest()[:12]
        return 'aw-ops-' + slug + '-' + digest

    def ensure_bucket(self, deployment_id):
        bucket = self._bucket(deployment_id)
        identity = {'schemaVersion': 1, 'accountUid': self.identity(), 'region': self.region, 'deploymentId': deployment_id}
        existing = self.get(bucket, 'identity.json')
        if existing is not None and _json(existing) != identity:
            raise StoreError('Operations bucket identity does not match this deployment')
        # PutBucket is idempotent for a bucket owned by this account; an unrelated
        # bucket is never reused unless its stored deployment identity matches.
        info = self._api('get-bucket-info', bucket, missing=True)
        if info is not None and existing is None:
            raise StoreError('Existing operations bucket has no deployment identity; manual reconciliation required')
        if info is None:
            self._api('put-bucket', bucket, '--acl', 'private')
        self._unversioned(bucket)
        self._api('put-bucket-acl', bucket, '--acl', 'private')
        self._api('put-bucket-encryption', bucket, '--server-side-encryption-rule',
                  json.dumps({'ApplyServerSideEncryptionByDefault': {'SSEAlgorithm': 'AES256'}}))
        self._api('put-bucket-tags', bucket, '--tagging', json.dumps({'TagSet': {'Tag': [
            {'Key': 'auto-wonder-purpose', 'Value': 'operations'},
            {'Key': 'auto-wonder-deployment', 'Value': deployment_id}]}}))
        if existing is None:
            try:
                self.put(bucket, 'identity.json', json.dumps(identity, sort_keys=True).encode(), create_only=True)
            except ConflictError:
                if self.get(bucket, 'identity.json') != json.dumps(identity, sort_keys=True).encode():
                    raise StoreError('Operations bucket initialization conflicted') from None
        return bucket

    def discover(self):
        found, marker, seen = [], '', set()
        while True:
            args = ['--prefix', 'aw-ops-'] + (['--marker', marker] if marker else [])
            page = self._api('list-buckets', None, *args)
            if 'Buckets' not in page:
                raise StoreError('OSS bucket listing is invalid')
            buckets = page['Buckets']
            if isinstance(buckets, dict):
                if 'Bucket' not in buckets:
                    raise StoreError('OSS bucket listing is invalid')
                buckets = buckets['Bucket']
            # ossutil converts XML singleton elements to objects and empty
            # elements to null, while repeated elements become arrays.
            if buckets is None:
                buckets = []
            elif isinstance(buckets, dict):
                buckets = [buckets]
            if not isinstance(buckets, list) or any(not isinstance(item, dict) for item in buckets):
                raise StoreError('OSS bucket listing is invalid')
            for item in buckets:
                name = item.get('Name', '')
                location = item.get('Location', '').removeprefix('oss-')
                if not name.startswith('aw-ops-') or location != self.region:
                    continue
                raw = self.get(name, 'identity.json')
                if raw is None:
                    raise StoreError('An operations bucket has no deployment identity')
                identity = _json(raw)
                if (identity.get('schemaVersion') != 1 or identity.get('accountUid') != self.identity()
                        or identity.get('region') != self.region or not isinstance(identity.get('deploymentId'), str)
                        or name != self._bucket(identity['deploymentId'])):
                    raise StoreError('Discovered operations bucket identity is invalid')
                found.append(dict(identity, bucket=name))
            if page.get('IsTruncated') not in (True, 'true'):
                return found
            marker = page.get('NextMarker')
            if not isinstance(marker, str) or not marker or marker in seen:
                raise StoreError('OSS bucket listing pagination is invalid')
            seen.add(marker)
