bootstrap = '''
import foundry_api
import sys
import types

context = sys.argv[1]
foundry_api.standard_library._init(context)

def builtin(name):
	if isinstance(__builtins__, types.ModuleType):
		return __builtins__.__dict__[name]
	else:
		return __builtins__[name]

def print(*args, **kwargs):
	builtin('print')(*args, flush=True, **kwargs)

script_name = 'main'
script_body = sys.stdin.read()
script_globals = {
	'__name__': '__main__',
	'__file__': script_name,
	'__loader__': __loader__,
	'__package__': None,
	'__spec__': None,
	'__annotations__': {},
	'print': print
}

for exported_name in dir(foundry_api):
	if exported_name[0] != '_':
		exported_object = foundry_api.__dict__[exported_name]
		script_globals[exported_name] = exported_object

code = compile(script_body + '\\n', script_name, 'exec')
exec(code, script_globals)
'''