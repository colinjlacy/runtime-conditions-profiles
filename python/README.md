# Python Runtime Conditions AST Profiler

This tree contains the Python implementation of declaration-code Runtime Conditions Profile generation.

## Layout

- `runtimeconditions/`: typed no-op declaration library and Python AST profiler.
- `profiler/main.py`: CLI wrapper for generating Runtime Conditions Profile YAML.
- `apps/`: sample Python services.
- `docker-compose.yml`: Python sample service stack.

## Generate a Profile

```sh
python3 -m runtimeconditions.profiler -d apps/traffic -n traffic-generator
```

or:

```sh
python3 profiler/main.py -d apps/traffic -n traffic-generator
```

## Run Samples

```sh
python3 -m pip install -r requirements.txt
docker compose up
```

## Test

```sh
python3 -m unittest discover -s tests
```
