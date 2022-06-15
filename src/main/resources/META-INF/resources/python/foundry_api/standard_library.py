# Imports hidden within the user's namespace:
import json as _json
import platform as _platform
import socket as _socket
import struct as _struct
import threading as _threading
import traceback as _traceback
import concurrent.futures as _concurrent_futures
from colorsys import hsv_to_rgb as _hsv_to_rgb
# Imports available within the user's namespace:
time = __import__('time')
math = __import__('math')
random = __import__('random')


_script_context = None


def _init(context):
    global _script_context
    _script_context = context


def _platform_variant():
    return 'arm' if 'arm' in _platform.machine() else 'x64'


class _DaemonClient:

    def __init__(self, stream):
        self._stream = stream
        self._correlation_accumulator = 0
        self._send_lock = _threading.Lock()
        self._outstanding_requests = {}
        self._outstanding_requests_lock = _threading.Lock()

        def receiver_worker():
            try:
                while True:
                    buffer = bytearray()
                    while len(buffer) < 6:
                        chunk = self._stream.recv(6 - len(buffer))
                        if len(chunk) == 0:
                            raise RuntimeError('Empty chunk received, perhaps the stream has terminated.')
                        buffer.extend(chunk)
                    length = buffer[0] + 256 * buffer[1] + 65536 * buffer[2] + 16777216 * buffer[3]
                    correlation_number = buffer[4] + 256 * buffer[5]
                    while len(buffer) < length:
                        chunk = self._stream.recv(length - len(buffer))
                        buffer.extend(chunk)
                    with self._outstanding_requests_lock:
                        callback = self._outstanding_requests.pop(correlation_number)
                        if callback is not None:
                            try:
                                callback(buffer)
                            except Exception as exception:
                                print('Callback error in proxy receiver: ' + str(exception), flush=True)
            except Exception as exception:
                print(_traceback.format_exc(), flush=True)
                print('Unhandled fatal error in proxy receiver: ' + str(exception), flush=True)

        self._receiver_thread = _threading.Thread(target=receiver_worker, daemon=True)

    def start(self):
        self._receiver_thread.start()

    def send(self, buffer, callback):
        with self._send_lock:
            correlation_number = self._correlation_accumulator % 65536  # TODO: Expire timed-out requests.
            _struct.pack_into("<I", buffer, 0, len(buffer))
            _struct.pack_into("<H", buffer, 4, correlation_number)
            with self._outstanding_requests_lock:
                self._outstanding_requests[correlation_number] = callback  # Add before sending (response could proceed add).
            self._stream.sendall(buffer)
            self._correlation_accumulator += 1


class _ModuleProxy:

    class Result:

        def __init__(self):
            self._value = None
            self._event = _threading.Event()

        def wait(self, timeout=None):
            fulfilled = self._event.wait(timeout)
            if fulfilled:
                return self._value
            else:
                raise TimeoutError()

        def fulfil(self, buffer):
            if len(buffer) > 0:
                decoded = buffer.decode('ASCII')
                self._value = _json.loads(decoded)
            else:
                self._value = None
            self._event.set()

    def __init__(self, daemon_client, module_name):
        self._daemon_client = daemon_client
        self._module_name = module_name
        self._field_names = []
        self._method_names = ['get_fields', 'set_fields']

    def __setattr__(self, attribute_name, value):
        if attribute_name[0] == '_':  # Protected attribute of the proxy itself.
            object.__setattr__(self, attribute_name, value)
        elif attribute_name in self._field_names:
            field_name = attribute_name
            self.set_fields({field_name: value}, asynchronous=True)
        else:
            raise AttributeError('"' + self._module_name + '" module has no field "' + attribute_name + '"')

    def __getattr__(self, attribute_name):
        if attribute_name in self._field_names:
            field_name = attribute_name
            fields = self.get_fields()  # TODO: Have daemon push field updates instead?
            if field_name in fields:
                return fields[field_name]
            else:
                return None
        elif attribute_name in self._method_names:
            method_name = attribute_name

            def method(*args, **kwargs):  # TODO: Construct method once on object initialisation.
                request = {
                    'arguments': {
                        **{index: self.normalise_value(value) for index, value in enumerate(args)},
                        **{name: self.normalise_value(value) for name, value in kwargs.items()},
                    } if len(kwargs) > 0 else [self.normalise_value(value) for value in args]
                }
                command = 16  # Method.
                type_code = 1  # Module.
                packet = bytearray(10)  # Initial length (for constant size header).
                _struct.pack_into("<xxxxxxHBB", packet, 0, command, type_code, len(self._module_name))
                packet.extend(self._module_name.encode('ASCII'))
                packet.append(len(method_name))
                packet.extend(method_name.encode('ASCII'))
                packet.extend(_json.dumps(request).encode('ASCII'))  # TODO: Write using .dump() and BytesIO?
                result = _ModuleProxy.Result()
                self._daemon_client.send(packet, lambda buffer: result.fulfil(buffer[6:]))
                dictionary = result.wait()
                if dictionary is None:
                    return None
                elif 'result' in dictionary:
                    return dictionary['result']
                elif 'error' in dictionary:
                    print('Error invoking method: ' + self._module_name  + '.' + method_name, flush=True)
                    raise RuntimeError(dictionary['error'])
                else:
                    raise RuntimeError('Error invoking method: ' + self._module_name  + '.' + method_name)

            return method
        else:
            raise AttributeError('"' + self._module_name + '" module has no field or method "' + attribute_name + '"')

    def normalise_key(self, unnormalised): # Normalise key to be serialised.
        if isinstance(unnormalised, int):
            return str(unnormalised)
        if isinstance(unnormalised, tuple):
            return ','.join(self.normalise_key(key) for key in unnormalised)
        else:
            return unnormalised

    def normalise_value(self, unnormalised): # Normalise value to be serialised.
        if isinstance(unnormalised, dict):
            return {self.normalise_key(key): self.normalise_value(value) for key, value in unnormalised.items()}
        else:
            return unnormalised


class _Buttons(_ModuleProxy):

    def __init__(self, daemon_client):
        super().__init__(daemon_client, 'buttons')
        self._field_names.extend(['bottom_pressed', 'bottom_pressed_count', 'middle_pressed', 'middle_pressed_count', 'top_pressed', 'top_pressed_count'])  # TODO: Populate dynamically.
        self._method_names.extend(['get_next_action'])  # TODO: Populate dynamically.


class _EnvSensor(_ModuleProxy):

    def __init__(self, daemon_client):
        super().__init__(daemon_client, 'env_sensor')
        self._field_names.extend(['humidity', 'pressure', 'temperature'])  # TODO: Populate dynamically.
        self._method_names.extend([])  # TODO: Populate dynamically.


class _LightSensor(_ModuleProxy):

    def __init__(self, daemon_client):
        super().__init__(daemon_client, 'light_sensor')
        self._field_names.extend(['ambient_light', 'blue', 'green', 'last_gesture', 'num_gestures', 'num_times_within_proximity', 'red', 'within_proximity'])  # TODO: Populate dynamically.
        self._method_names.extend(['get_next_gesture'])  # TODO: Populate dynamically.


class _Display(_ModuleProxy):

    def __init__(self, daemon_client):
        super().__init__(daemon_client, 'display')
        self._field_names.extend(['brightness', 'estimated_current', 'gamma_correction_blue', 'gamma_correction_enabled', 'gamma_correction_green', 'gamma_correction_red', 'max_current', 'panel_height', 'panel_width', 'refresh_period', 'show'])  # TODO: Populate dynamically.
        self._method_names.extend(['set', 'scroll_text'])  # TODO: Populate dynamically.

    def set_leds(self, x_y_to_colour, show=True):  # TODO: For performance we have duplicated this method here, but the long-term solution is to implement it in Java.
        colours = {}
        for x_y, colour in x_y_to_colour.items():
            x, y = x_y
            if x < 8 and y < 8:
                colours[63 - x - 8 * y] = colour
            elif x < 16 and y < 8:
                colours[7 - y + 8 * x] = colour
            elif x < 8 and y < 16:
                colours[176 + y - 8 * x] = colour
            else:
                pass  # Silently ignore invalid coordinates.
        run_async(self.set, colours, show=show)  # TODO: We should automatically provide async_* versions of all methods, rather than using run_async.

    def set_3d(self, x_y_z_to_colour, show=True):  # TODO: For performance we have duplicated this method here, but the long-term solution is to implement it in Java.
        colours = {}
        for x_y_z, colour in x_y_z_to_colour.items():
            x, y, z = x_y_z
            if x < 8 and y < 8 and z == 8:
                colours[63 - x - 8 * y] = colour
            elif y < 8 and z < 8 and x == 8:
                colours[127 - y - 8 * z] = colour
            elif z < 8 and x < 8 and y == 8:
                colours[191 - z - 8 * x] = colour
            else:
                pass  # Silently ignore invalid coordinates.
        run_async(self.set, colours, show=show)  # TODO: We should automatically provide async_* versions of all methods, rather than using run_async.

    def set_led(self, x, y, colour, show=True):  # TODO: For performance we have duplicated this method here, but the long-term solution is to implement it in Java.
        self.set_leds({(x, y): colour}, show=show)

    def set_all(self, colour, show=True):  # TODO: For performance we have duplicated this method here, but the long-term solution is to implement it in Java.
        x_y_to_colour = {}
        for y in range(0, 16):
            for x in range(0, 16):
                if x < 8 or y < 8:
                    x_y_to_colour[x, y] = colour
        self.set_leds(x_y_to_colour, show=show)

    def set_panel(self, panel, colour_array, show=True):  # TODO: For performance we have duplicated this method here, but the long-term solution is to implement it in Java.
        (start_x, y) = (0, 7)
        if panel == 'top':
            y += 8
        elif panel == 'right':
            start_x += 8
        elif panel != 'left':
            raise Exception("The panel must be 'left', 'right', or 'top'")
        x_y_to_colour = {}
        for colours in colour_array:
            x = start_x
            for colour in colours:
                x_y_to_colour[x, y] = colour
                x += 1
            y -= 1
        self.set_leds(x_y_to_colour, show=show)


class _Screen(_ModuleProxy):

    def __init__(self, daemon_client):
        super().__init__(daemon_client, 'screen')
        self._field_names.extend(['invert_colours', 'resolution_scaling', 'rotation'])  # TODO: Populate dynamically.
        self._method_names.extend(['set_pixel', 'set_pixels', 'draw_rectangle', 'set_half_row', 'write_text', 'draw_image'])  # TODO: Populate dynamically.


class _Speaker(_ModuleProxy):

    def __init__(self, daemon_client):
        super().__init__(daemon_client, 'speaker')
        self._field_names.extend(['volume'])  # TODO: Populate dynamically.
        self._method_names.extend(['play', 'stop', 'say', 'tone'])  # TODO: Populate dynamically.


class _Microphone(_ModuleProxy):

    def __init__(self, daemon_client):
        super().__init__(daemon_client, 'microphone')
        self._field_names.extend([])  # TODO: Populate dynamically.
        self._method_names.extend(['start_recording', 'stop_recording', 'start_voice_recognition', 'wait_for_sentence', 'stop_voice_recognition', 'start_recording_for_frequency_analysis', 'get_frequency_buckets'])  # TODO: Populate dynamically.


class _IMU(_ModuleProxy):

    def __init__(self, daemon_client):
        super().__init__(daemon_client, 'imu')
        self._field_names.extend(['roll', 'pitch', 'yaw', 'gravity_x', 'gravity_y', 'gravity_z', 'acceleration_x', 'acceleration_y', 'acceleration_z'])  # TODO: Populate dynamically.
        self._method_names.extend([])  # TODO: Populate dynamically.


class _Pi(_ModuleProxy):

    def __init__(self, daemon_client):
        super().__init__(daemon_client, 'pi')
        self._field_names.extend([])  # TODO: Populate dynamically.
        self._method_names.extend(['ip_address', 'cpu_temp', 'cpu_percent', 'ram_percent_used', 'disk_percent'])  # TODO: Populate dynamically.


class _OpenSimplex:

    # Shared binaries built using Docker:

    # #!/bin/bash
    #
    # apt install qemu-user-static binfmt-support
    #
    # cat > Dockerfile <<\EOF
    #
    # FROM debian:buster-20210111
    #
    # RUN apt update && apt install -y git build-essential
    #
    # RUN git clone https://github.com/smcameron/open-simplex-noise-in-c /root/OpenSimplex
    # WORKDIR /root/OpenSimplex
    # RUN gcc -O2 -fPIC -c -o open-simplex-noise.o open-simplex-noise.c
    # RUN gcc -O2 -shared -o open-simplex-noise.so open-simplex-noise.o
    #
    # EOF
    #
    # docker image rm debian:buster-20210111 # Work around Docker not supporting several platform-specific versions of the same image.
    # docker build --platform linux/amd64 -t build-simplex-x64 .
    # docker run --rm --entrypoint cat build-simplex-x64 /root/OpenSimplex/open-simplex-noise.so > open-simplex-noise-x64.so
    # chmod +x open-simplex-noise-x64.so
    #
    # docker image rm debian:buster-20210111 # Work around Docker not supporting several platform-specific versions of the same image.
    # docker build --platform linux/arm/v7 -t build-simplex-arm .
    # docker run --rm --entrypoint cat build-simplex-arm /root/OpenSimplex/open-simplex-noise.so > open-simplex-noise-arm.so
    # chmod +x open-simplex-noise-arm.so

    def __init__(self):
        self._lib = None

    def _connect_ffi(self):
        from cffi import FFI

        ffi = FFI()
        variant = _platform_variant()
        lib = ffi.dlopen(_script_context + '/noise/open-simplex-noise-' + variant + '.so')
        seed = 123456

        ffi.cdef('''
int open_simplex_noise(int64_t seed, struct osn_context **ctx);
double open_simplex_noise2(const struct osn_context *ctx, double x, double y);
double open_simplex_noise3(const struct osn_context *ctx, double x, double y, double z);
double open_simplex_noise4(const struct osn_context *ctx, double x, double y, double z, double w);
        ''')

        ctx = ffi.new('struct osn_context **')
        if lib.open_simplex_noise(seed, ctx) != 0:
            raise 'Failed to initialise C OpenSimplex library'

        self._lib = lib
        self._ctx = ctx

    def _ensure_ffi_connected(self):
        if self._lib is None:
            self._connect_ffi()

    def noise2(self, x, y):
        self._ensure_ffi_connected()
        return self._lib.open_simplex_noise2(self._ctx[0], x, y)

    def noise3(self, x, y, z):
        self._ensure_ffi_connected()
        return self._lib.open_simplex_noise3(self._ctx[0], x, y, z)

    def noise4(self, x, y, z, w):
        self._ensure_ffi_connected()
        return self._lib.open_simplex_noise4(self._ctx[0], x, y, z, w)


class LumiCube:

    def __init__(self, address=None, port=2020):
        if address is None:
            stream = _socket.socket(_socket.AF_UNIX, _socket.SOCK_STREAM)
            stream.connect('/tmp/foundry_daemon.sock')
        else:
            stream = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
            stream.connect((address, port))
        self._daemon_client = _DaemonClient(stream)
        self._daemon_client.start()
        # TODO: Add these objects dynamically based upon the addressable module names:
        self.buttons = _Buttons(self._daemon_client)
        self.env_sensor = _EnvSensor(self._daemon_client)
        self.light_sensor = _LightSensor(self._daemon_client)
        self.display = _Display(self._daemon_client)
        self.screen = _Screen(self._daemon_client)
        self.speaker = _Speaker(self._daemon_client)
        self.microphone = _Microphone(self._daemon_client)
        self.imu = _IMU(self._daemon_client)
        self.pi = _Pi(self._daemon_client)


cube = LumiCube()
buttons = cube.buttons
env_sensor = cube.env_sensor
light_sensor = cube.light_sensor
display = cube.display
screen = cube.screen
speaker = cube.speaker
microphone = cube.microphone
imu = cube.imu
pi = cube.pi


black = 0
grey = 0x808080
white = 0xFFFFFF
red = 0xFF0000
orange = 0xFF8C00
yellow = 0xFFFF00
green = 0x00FF00
cyan = 0x00FFFF
blue = 0x0000FF
magenta = 0xFF00FF
pink = 0xFF007F
purple = 0x800080


sine_wave = 'sine_wave'
square_wave = 'square_wave'
white_noise = 'white_noise'


def hsv_colour(hue, sat, val):
    hue = hue % 1
    sat = max(0, min(sat, 1.0))
    val = max(0, min(val, 1.0))
    (r, g, b) = _hsv_to_rgb(hue, sat, val)
    return (int(r * 255) << 16) + (int(g * 255) << 8) + int(b * 255)


def random_colour():
    return hsv_colour(random.random(), 1, 1)


_noise = _OpenSimplex()
noise_2d = _noise.noise2
noise_3d = _noise.noise3
noise_4d = _noise.noise4


_global_pool_lock = _threading.Lock()
_global_pool = None


def run_async(task, *args, **kwargs):
    global _global_pool
    if not callable(task):
        raise ValueError('Expected function, method, or other callable')
    with _global_pool_lock:
        if _global_pool is None:
            _global_pool = _concurrent_futures.ThreadPoolExecutor()
        return _global_pool.submit(task, *args, **kwargs)
