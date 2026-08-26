#!/usr/bin/env python3

import sys
from pathlib import Path


CLIENT_DIR = (
    Path(__file__).resolve().parents[2] / "tests" / "integration-client"
)
sys.path.insert(0, str(CLIENT_DIR))

import cattle  # noqa: E402


def find_instance(instance):
    hosts = instance.hosts()
    if len(hosts) > 0:
        return hosts[0].agent().uuid == 'test-agent'
    return False


client = cattle.from_env()

UUID = 'docker0-agent-instance-provider'
nsp = client.list_network_service_provider(uuid=UUID)[0]
instances = [
    instance for instance in nsp.instances() if find_instance(instance)
]

if len(instances) != 1:
    raise Exception('Found {} instances, expect 1.  Try running a container'
                    'first'.format(len(instances)))

account = instances[0].agent().account()

found = False
for cred in account.credentials():
    if cred.kind == 'apiKey' and cred.publicValue == 'ai':
        found = True

if not found:
    print("Creating credential for account", account.id)
    client.create_credential(accountId=account.id,
                             publicValue='ai',
                             secretValue='aipass',
                             kind='apiKey')
