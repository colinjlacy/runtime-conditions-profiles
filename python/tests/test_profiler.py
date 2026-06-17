from __future__ import annotations

import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

PYTHON_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PYTHON_ROOT))

from runtimeconditions.profiler import extract_dir


class ProfilerTest(unittest.TestCase):
    def test_extract_dir_with_aliased_import(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = textwrap.dedent(
                """
                from dataclasses import dataclass
                import runtimeconditions as runcon

                TODO_PATH = "/todos"
                EVENTS_SUBJECT = "todos.changed"

                @dataclass
                class CreateTodoRequest:
                    userId: int
                    title: str

                @dataclass
                class Todo:
                    id: int
                    userId: int
                    title: str
                    completed: bool

                @dataclass
                class TodoEvent:
                    todo: Todo

                runcon.API(
                    "todos-api",
                    runcon.POST(
                        TODO_PATH,
                        runcon.Request(CreateTodoRequest),
                        runcon.Response(Todo),
                    ),
                )

                runcon.Datastore("primary-db", runcon.Relational(runcon.MySQL))
                runcon.Cache("todo-cache", runcon.KeyValue(runcon.Redis))
                runcon.MessageBus(
                    "todo-events",
                    runcon.PubSub(runcon.NATS),
                    runcon.Publishes(EVENTS_SUBJECT, runcon.Payload(TodoEvent)),
                )
                """
            )
            Path(temp_dir, "main.py").write_text(source, encoding="utf-8")

            profile = extract_dir(
                temp_dir,
                name="todos",
                workload_uri="github.com/example/todos",
                workload_version="v0.1.0",
            )

        self.assertEqual(
            profile["extensions"],
            ["core", "runtimeconditions.io/message-bus/v1alpha1"],
        )
        self.assertEqual(len(profile["conditions"]), 4)

        api = profile["conditions"][0]
        self.assertEqual(api["name"], "todos-api")
        self.assertEqual(api["kind"], "api")
        operation = api["interface"]["operations"][0]
        self.assertEqual(operation["path"], "/todos")
        self.assertEqual(operation["requestBodySchema"]["userId"], "integer")
        self.assertEqual(operation["responseSchema"]["completed"], "boolean")

        datastore = profile["conditions"][1]
        self.assertEqual(datastore["interface"]["type"], "relational")
        self.assertEqual(datastore["interface"]["engine"], "mysql")

        cache = profile["conditions"][2]
        self.assertEqual(cache["interface"]["type"], "key_value")
        self.assertEqual(cache["interface"]["engine"], "redis")

        message_bus = profile["conditions"][3]
        self.assertEqual(message_bus["kind"], "runtimeconditions.message_bus")
        self.assertEqual(message_bus["interface"]["engine"], "nats")
        self.assertEqual(
            message_bus["interface"]["subjects"][0]["payloadSchema"]["todo"]["title"],
            "string",
        )


if __name__ == "__main__":
    unittest.main()
