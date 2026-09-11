"""Interoperability check for the Moqui A2A server, driven by the official a2a-sdk client.

    pip install a2a-sdk
    python interop_a2a_sdk.py [baseUrl] [user:password]

The instance must run with a2a_enabled=true. SendMessage completes only when the a2a_default_profile LLM
profile has a model configured; without one the task ends in TASK_STATE_FAILED and that check fails.
"""

import asyncio
import os
import sys
import uuid

import a2a.types as t
import httpx
from a2a.client import ClientConfig, create_client

BASE = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('A2A_BASE_URL', 'http://localhost:8080')
USER, PASSWORD = (sys.argv[2].split(':', 1) if len(sys.argv) > 2
                  else (os.environ.get('A2A_USER', 'john.doe'), os.environ.get('A2A_PASSWORD', 'moqui')))

passed: list[str] = []
failed: list[str] = []


def check(name: str, condition: bool, detail: object = '') -> None:
    (passed if condition else failed).append(name)
    print(f"  {'ok  ' if condition else 'FAIL'} {name}{': ' + str(detail) if detail else ''}")


def message(text: str) -> t.Message:
    return t.Message(message_id=str(uuid.uuid4()), role=t.Role.ROLE_USER, parts=[t.Part(text=text)])


def payload_of(event: object) -> tuple[str, object]:
    value = getattr(event, 'root', event)
    which = value.WhichOneof('payload') if hasattr(value, 'WhichOneof') else None
    return which or type(value).__name__, value


async def main() -> None:
    async with httpx.AsyncClient(auth=httpx.BasicAuth(USER, PASSWORD), timeout=180) as http_client:
        print('== discovery from /.well-known/agent-card.json')
        client = await create_client(BASE, ClientConfig(httpx_client=http_client, streaming=False))
        card = getattr(client, 'card', None) or getattr(client, '_card', None)
        if card is not None:
            interface = card.supported_interfaces[0]
            check('agent card resolved', bool(card.name), f'{card.name} {card.version}')
            check('JSONRPC 1.0 interface',
                  interface.protocol_binding == 'JSONRPC' and interface.protocol_version == '1.0',
                  f'{interface.protocol_binding} {interface.protocol_version} {interface.url}')
            check('capabilities match the implementation',
                  card.capabilities.streaming and not card.capabilities.push_notifications,
                  f'streaming={card.capabilities.streaming} push={card.capabilities.push_notifications}')

        print('== SendMessage')
        task = None
        async for event in client.send_message(t.SendMessageRequest(message=message('Hello, who are you?'))):
            which, value = payload_of(event)
            print(f'  event: {which}')
            if which == 'task':
                task = value.task
            elif type(value).__name__ == 'Task':
                task = value
        check('SendMessage returns a Task', task is not None)

        if task is not None:
            check('task completed', t.TaskState.Name(task.status.state) == 'TASK_STATE_COMPLETED',
                  t.TaskState.Name(task.status.state))
            check('response artifact', bool(task.artifacts) and bool(task.artifacts[0].parts[0].text),
                  task.artifacts[0].parts[0].text if task.artifacts else 'none')
            check('history has the user and agent messages',
                  [t.Role.Name(m.role) for m in task.history] == ['ROLE_USER', 'ROLE_AGENT'],
                  [t.Role.Name(m.role) for m in task.history])

            print('== GetTask / ListTasks / CancelTask')
            fetched = await client.get_task(t.GetTaskRequest(id=task.id, history_length=10))
            check('GetTask', fetched.id == task.id, f'{fetched.id[:8]} {t.TaskState.Name(fetched.status.state)}')
            listed = await client.list_tasks(t.ListTasksRequest())
            check('ListTasks', any(other.id == task.id for other in listed.tasks),
                  f"{len(listed.tasks)} tasks, nextPageToken='{listed.next_page_token}'")
            try:
                await client.cancel_task(t.CancelTaskRequest(id=task.id))
                check('CancelTask on a terminal task is refused', False, 'accepted')
            except Exception as error:  # noqa: BLE001 - the SDK raises its own typed errors
                check('CancelTask on a terminal task is refused', True,
                      f'{type(error).__name__}: {str(error)[:90]}')
            try:
                await client.get_task(t.GetTaskRequest(id='does-not-exist'))
                check('GetTask of an unknown task is refused', False, 'accepted')
            except Exception as error:  # noqa: BLE001
                check('GetTask of an unknown task is refused', True,
                      f'{type(error).__name__}: {str(error)[:90]}')

        print('== SendStreamingMessage')
        streaming = await create_client(BASE, ClientConfig(httpx_client=http_client, streaming=True))
        seen: list[str] = []
        async for event in streaming.send_message(t.SendMessageRequest(message=message('Answer as a stream'))):
            which, _ = payload_of(event)
            seen.append(which)
        check('stream carries the Task and its updates',
              'task' in seen and any('update' in name for name in seen), seen)

        print('== GetExtendedAgentCard')
        extended = await client.get_extended_agent_card(t.GetExtendedAgentCardRequest())
        check('extended card', bool(extended.skills), f'{extended.name}, {len(extended.skills)} skills')

    print(f'\n{len(passed)} passed, {len(failed)} failed' + (f' -> {failed}' if failed else ''))
    sys.exit(1 if failed else 0)


asyncio.run(main())
