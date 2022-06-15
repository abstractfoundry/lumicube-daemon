import json
import math
import os
import random
import subprocess
import time
import threading
import traceback
import waitress
from flask import Flask, request


SOCKET_PATH = '/tmp/foundry_python_service.sock'


global_phase = 0  # Increment to interrupt any currently executing methods.
global_phase_lock = threading.Lock()


def install_endpoints(service):

    @service.route('/ping', methods=['POST'])
    def ping():
        body = request.get_json()
        return {'status': 0, 'result': body['value']}

    @service.route('/interrupt_executing_methods', methods=['POST'])
    def interrupt_executing_methods():
        global global_phase
        with global_phase_lock:
            current_phase = global_phase
            new_phase = current_phase + 1
            global_phase = new_phase
        speaker_stop(None)
        microphone_stop_voice_recognition(None)
        return {'status': 0, 'phase': new_phase}

    @service.route('/invoke_module_method', methods=['POST'])
    def invoke_module_method():
        try:
            body = request.get_json()
            module, method, encoded_json = body['module'], body['method'], body['json']
            decoded_json = json.loads(encoded_json)
            arguments = decoded_json['arguments']
            if isinstance(arguments, list):
                args, kwargs = arguments, {}
            elif isinstance(arguments, dict):
                args, index = [], 0
                while True:
                    string = str(index)
                    if string in arguments:
                        argument = arguments.pop(string)
                        args.append(argument)
                    else:
                        break
                    index += 1
                kwargs = arguments
            else:
                raise RuntimeError('Expected either a list or a dict or arguments')
            if module == 'display' and method == 'set_leds':
                from foundry_api import display
                result = display_set_leds(display, *args, **kwargs)
            elif module == 'display' and method == 'set_led':
                from foundry_api import display
                result = display_set_led(display, *args, **kwargs)
            elif module == 'display' and method == 'set_all':
                from foundry_api import display
                result = display_set_all(display, *args, **kwargs)
            elif module == 'display' and method == 'set_3d':
                from foundry_api import display
                result = display_set_3d(display, *args, **kwargs)
            elif module == 'display' and method == 'set_panel':
                from foundry_api import display
                result = display_set_panel(display, *args, **kwargs)
            elif module == 'display' and method == 'scroll_text':
                from foundry_api import display
                result = display_scroll_text(display, *args, **kwargs)
            elif module == 'buttons' and method == 'get_next_action':
                from foundry_api import buttons
                result = buttons_get_next_action(buttons, *args, **kwargs)
            elif module == 'light_sensor' and method == 'get_next_gesture':
                from foundry_api import light_sensor
                result = light_sensor_get_next_gesture(light_sensor, *args, **kwargs)
            elif module == 'screen' and method == 'set_pixel':
                from foundry_api import screen
                result = screen_set_pixel(screen, *args, **kwargs)
            elif module == 'screen' and method == 'set_pixels':
                from foundry_api import screen
                result = screen_set_pixels(screen, *args, **kwargs)
            elif module == 'screen' and method == 'draw_image':
                from foundry_api import screen
                result = screen_draw_image(screen, *args, **kwargs)
            elif module == 'speaker' and method == 'say':
                from foundry_api import speaker
                result = speaker_say(speaker, *args, **kwargs)
            elif module == 'speaker' and method == 'play':
                from foundry_api import speaker
                result = speaker_play(speaker, *args, **kwargs)
            elif module == 'speaker' and method == 'stop':
                from foundry_api import speaker
                result = speaker_stop(speaker, *args, **kwargs)
            elif module == 'speaker' and method == 'tone':
                from foundry_api import speaker
                result = speaker_tone(speaker, *args, **kwargs)
            elif module == 'microphone' and method == 'start_recording':
                from foundry_api import microphone
                result = microphone_start_recording(microphone, *args, **kwargs)
            elif module == 'microphone' and method == 'stop_recording':
                from foundry_api import microphone
                result = microphone_stop_recording(microphone, *args, **kwargs)
            elif module == 'microphone' and method == 'start_voice_recognition':
                from foundry_api import microphone
                result = microphone_start_voice_recognition(microphone, *args, **kwargs)
            elif module == 'microphone' and method == 'wait_for_sentence':
                from foundry_api import microphone
                result = microphone_wait_for_sentence(microphone, *args, **kwargs)
            elif module == 'microphone' and method == 'stop_voice_recognition':
                from foundry_api import microphone
                result = microphone_stop_voice_recognition(microphone, *args, **kwargs)
            elif module == 'microphone' and method == 'start_recording_for_frequency_analysis':
                from foundry_api import microphone
                result = microphone_start_recording_for_frequency_analysis(microphone, *args, **kwargs)
            elif module == 'microphone' and method == 'get_frequency_buckets':
                from foundry_api import microphone
                result = microphone_get_frequency_buckets(microphone, *args, **kwargs)
            elif module == 'pi' and method == 'ip_address':
                result = pi_ip_address(*args, **kwargs)
            elif module == 'pi' and method == 'cpu_temp':
                result = pi_cpu_temp(*args, **kwargs)
            elif module == 'pi' and method == 'cpu_percent':
                result = pi_cpu_percent(*args, **kwargs)
            elif module == 'pi' and method == 'ram_percent_used':
                result = pi_ram_percent_used(*args, **kwargs)
            elif module == 'pi' and method == 'disk_percent':
                result = pi_disk_percent(*args, **kwargs)
            else:
                raise RuntimeError('No such method: ' + module + '.' + method)
            return {'status': 0, 'result': result}
        except Exception as exception:
            print(traceback.format_exc(), flush=True)
            return {'status': -1, 'error': type(exception).__name__ + ': ' + str(exception)}


def display_set_leds(display, coordinate_string_to_colour, show=True):  # TODO: For performance implement this in Java.
    colours = {}
    for coordinate_string, colour in coordinate_string_to_colour.items():
        x, y = [int(value) for value in coordinate_string.split(',')]
        if x < 8 and y < 8:
            colours[63 - x - 8 * y] = colour
        elif x < 16 and y < 8:
            colours[7 - y + 8 * x] = colour
        elif x < 8 and y < 16:
            colours[176 + y - 8 * x] = colour
        else:
            pass  # Silently ignore invalid coordinates.
    display.set(colours, show=show)


def display_set_led(display, x, y, colour, show=True):  # TODO: For performance implement this in Java.
    display.set_leds({(x, y): colour}, show=show)  # TODO: Join x and y into coordinate string and call display_set_leds directly?


def display_set_all(display, colour, show=True):  # TODO: For performance implement this in Java.
    x_y_to_colour = {}
    for y in range(0, 16):
        for x in range(0, 16):
            if x < 8 or y < 8:
                x_y_to_colour[x, y] = colour
    display.set_leds(x_y_to_colour, show=show)  # TODO: Join x and y into coordinate string and call display_set_leds directly?


def display_set_3d(display, coordinate_string_to_colour, show=True):  # TODO: For performance implement this in Java.
    colours = {}
    for coordinate_string, colour in coordinate_string_to_colour.items():
        x, y, z = [int(value) for value in coordinate_string.split(',')]
        if x < 8 and y < 8 and z == 8:
            colours[63 - x - 8 * y] = colour
        elif y < 8 and z < 8 and x == 8:
            colours[127 - y - 8 * z] = colour
        elif z < 8 and x < 8 and y == 8:
            colours[191 - z - 8 * x] = colour
        else:
            pass  # Silently ignore invalid coordinates.
    display.set(colours, show=show)


def display_set_panel(display, panel, colour_array, show=True):  # TODO: For performance implement this in Java.
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
    display.set_leds(x_y_to_colour, show=show)  # TODO: Join x and y into coordinate string and call display_set_leds directly?


def display_scroll_text(display, text, colour=0xFFFFFF, background_colour=0x0, speed=1, with_gap=True):
    with global_phase_lock:
        start_phase = global_phase
    def convert_image_to_array(value):
        import numpy as np
        new_array = []
        for row in np.asarray(value):
            new_array.append([int(pixel) for pixel in row])
        return new_array
    from PIL import Image, ImageDraw, ImageFont
    font = ImageFont.truetype('fonts/slkscr.ttf', 8)
    (width, height) = font.getsize(text)
    width += 16 * 2
    image = Image.new('I', (width, 8))
    draw = ImageDraw.Draw(image)
    draw.text((16, 7 - height), text, font=font, fill=colour)
    full_array = convert_image_to_array(image)
    period = 0.05 / speed
    for i in range(width):
        last_time = time.time()
        led_array = [row[i:i+17] for row in full_array]
        colours = {}
        for y, row in enumerate(led_array):
            for x, pixel_colour in enumerate(row):
                if pixel_colour == 0:
                    pixel_colour = background_colour
                if x > 8 and with_gap:
                    x = x - 1
                if x < 16:
                    colours[x, 7 - y] = pixel_colour
        while time.time() < (last_time + period):
            time.sleep(0.001)
        with global_phase_lock:
            if global_phase != start_phase:
                break
        display.set_leds(colours, True)  # TODO: Join x and y into coordinate string and call display_set_leds directly?


buttons_last_count = None


# TODO: Implement this to return the proper series of actions, rather
#       than one (biased) random action since the method was last called.
def buttons_get_next_action(buttons, timeout=None):
    global buttons_last_count
    with global_phase_lock:
        start_phase = global_phase
    start = time.time()
    result = None
    while (buttons_last_count is None) or (None in buttons_last_count):
        buttons_last_count = buttons_poll_count(buttons, 0.2)  # TODO: Honour timeout.
    count = buttons_last_count
    while time.time() < start + timeout if timeout is not None else True:
        count = buttons_poll_count(buttons, 0.2)
        if count[0] > buttons_last_count[0]:
            result = 'top'
            break
        elif count[1] > buttons_last_count[1]:
            result = 'middle'
            break
        elif count[2] > buttons_last_count[2]:
            result = 'bottom'
            break
        time.sleep(0.05)
        with global_phase_lock:
            if global_phase != start_phase:
                break
    buttons_last_count = count
    return result


def buttons_poll_count(buttons, timeout):
    try:
        fields = buttons.get_fields()
        names = ['top_pressed_count', 'middle_pressed_count', 'bottom_pressed_count']
        return [fields[name] if name in fields else None for name in names]
    except TimeoutError:
        return [None, None, None]  # TODO: Returning None due to timeout after initialisation loop will break logic.


light_sensor_last_count_and_gesture = None


# TODO: Implement this to return the proper series of actions, rather
#       than one (biased) random action since the method was last called.
def light_sensor_get_next_gesture(light_sensor, timeout=None):
    global light_sensor_last_count_and_gesture
    with global_phase_lock:
        start_phase = global_phase
    start = time.time()
    result = None
    while (light_sensor_last_count_and_gesture is None) or (None in light_sensor_last_count_and_gesture):
        light_sensor_last_count_and_gesture = light_sensor_poll_gesture(light_sensor, 0.2)  # TODO: Honour timeout.
    count_and_gesture = light_sensor_last_count_and_gesture
    while time.time() < start + timeout if timeout is not None else True:
        count_and_gesture = light_sensor_poll_gesture(light_sensor, 0.2)
        if count_and_gesture[0] > light_sensor_last_count_and_gesture[0] and count_and_gesture[1] > 0:
            if count_and_gesture[1] == 1:
                result = 'right'
            elif count_and_gesture[1] == 2:
                result = 'left'
            elif count_and_gesture[1] == 3:
                result = 'up'
            elif count_and_gesture[1] == 4:
                result = 'down'
            break
        time.sleep(0.05)
        with global_phase_lock:
            if global_phase != start_phase:
                break
    light_sensor_last_count_and_gesture = count_and_gesture
    return result


def light_sensor_poll_gesture(light_sensor, timeout):
    try:
        fields = light_sensor.get_fields()
        names = ['num_gestures', 'last_gesture']
        return [fields[name] if name in fields else None for name in names]
    except TimeoutError:
        return [None, None]  # TODO: Returning None due to timeout after above initialisation loop will break logic.


def screen_set_pixel(screen, x, y, colour):
    screen.draw_rectangle(x, y, 1, 1, colour)


def screen_set_pixels(screen, x=0, y=0, width=320, height=240, pixels=[]):
    # TODO: Consider the race condition when a user sets resolution_scaling and then calls this method, but resolution_scaling is read occurs before the new resolution is updated.

    with global_phase_lock:
        start_phase = global_phase

    # Configure the parameters.
    scaling = screen.resolution_scaling
    max_width = int(320 / scaling)
    max_height = int(240 / scaling)
    if x < 0 or x > max_width:
        raise Exception('x must be between 0 and ' + str(max_width))
    if y < 0 or y > max_height:
        raise Exception('y must be between 0 and ' + str(max_height))
    if width < 0 or x + width > max_width:
        raise Exception('x + width must be between 0 and ' + str(max_width))
    if height < 0 or y + height > max_height:
        raise Exception('y + height must be between 0 and ' + str(max_height))
    if len(pixels) != width * height:
        raise Exception('array must have length width * height')

    # Set the image using set_half_row.
    for j in range(height):
        data = []
        for i in range(width):
            data.append(pixels[j * width + i])
        with global_phase_lock:
            if global_phase != start_phase:
                break
        screen.set_half_row(x, y + j, data[:len(data)//2])
        screen.set_half_row(x + len(data)//2, y + j, data[len(data)//2:])


def screen_draw_image(screen, relative_or_absolute_path, x=0, y=0, width=320, height=240):
    # TODO: Sean says "Think what the interface should really be in terms of defaulted values (given the scaling changes the width and height)".

    from pathlib import Path
    from PIL import Image
    import numpy

    # Configure the parameters.
    scaling = screen.resolution_scaling
    max_width = int(320 / scaling)
    max_height = int(240 / scaling)
    if width == 320:
        width = max_width
    elif x < 0 or x > max_width:
        raise Exception('x must be between 0 and ' + str(max_width))
    if height == 240:
        height = max_height
    elif y < 0 or y > max_height:
        raise Exception('y must be between 0 and ' + str(max_height))
    if width < 0 or x + width > max_width:
        raise Exception('x + width must be between 0 and ' + str(max_width))
    if height < 0 or y + height > max_height:
        raise Exception('y + height must be between 0 and ' + str(max_height))

    desktop = os.path.expanduser('~/Desktop')
    path = str(Path(desktop) / Path(relative_or_absolute_path))  # If given relative path combines it with ~/Desktop prefix.
    img = Image.open(path)
    img = img.resize((width, height))
    arr = numpy.array(img)

    pixels = []
    for j, row in enumerate(arr):
        for i, colours in enumerate(row):
            (r, g, b) = colours
            pixel = int((r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF))
            pixels.append(pixel)
    screen.set_pixels(x, y, width, height, pixels)  # TODO: Directly call screen_set_pixels()?


pyttsx3_instance = None
pyttsx3_lock = threading.Lock()


def speaker_say(speaker, text):  # TODO: This should be interruptible (e.g. if the global_phase is incremented).
    global pyttsx3_instance
    with pyttsx3_lock:
        if pyttsx3_instance is None:
            import pyttsx3
            pyttsx3_instance = pyttsx3.init()
            pyttsx3_instance.setProperty('rate', 130)
            pyttsx3_instance.setProperty('volume', 0.5)
        pyttsx3_instance.say(text)
        pyttsx3_instance.runAndWait()


ffplay_instances = list()
ffplay_instances_lock = threading.Lock()


def speaker_play(speaker, relative_or_absolute_path):
    from subprocess import DEVNULL
    from pathlib import Path
    desktop = os.path.expanduser('~/Desktop')
    path = str(Path(desktop) / Path(relative_or_absolute_path))  # If given relative path combines it with ~/Desktop prefix.
    if not os.path.exists(path):
        raise FileNotFoundError(path)
    command = ['/usr/bin/ffplay', '-hide_banner', '-nostats', '-nodisp', '-autoexit', '-vn', path]
    instance = subprocess.Popen(command, stdin=DEVNULL, stdout=DEVNULL, stderr=DEVNULL)  # TODO: Provide some way to stop these processes.
    with ffplay_instances_lock:
        ffplay_instances.append(instance)


def speaker_stop(speaker):
    with ffplay_instances_lock:
        for instance in ffplay_instances:
            instance.terminate()
        ffplay_instances.clear()


def sine_wave(t, frequency):
    return math.sin(2 * math.pi * frequency * t)


def square_wave(t, frequency):
    return (t * frequency) % 1 < 0.5


def white_noise(t, frequency):
    return random.random()


def speaker_tone(speaker, frequency=261.626, duration=0.5, volume=0.25, function='sine_wave', ramp=0.0035, trailing_samples=512):
    global speaker_output_lock, speaker_output_stream
    with global_phase_lock:
        start_phase = global_phase

    period_size = 32  # Apparently we should write data in chunks of exactly one period.
    if 'speaker_output_stream' not in globals():
        import alsaaudio
        speaker_output_lock = threading.Lock()
        speaker_output_stream = alsaaudio.PCM(alsaaudio.PCM_PLAYBACK,  # TODO: This persistent output stream can interfere with other playback streams and cause audio to stutter and loop indefinitely, try to use PulseAudio in the future
                                              channels=1,
                                              rate=16000,
                                              format=alsaaudio.PCM_FORMAT_S16_LE,
                                              periodsize=period_size)
    sample_rate = 16000
    sample_count = int(duration * sample_rate)

    generator_function = sine_wave  # Default.
    if function == 'sine_wave':
        generator_function = sine_wave
    elif function == 'square_wave':
        generator_function = square_wave
    elif function == 'white_noise':
        generator_function = white_noise

    def modulation(t):
        value = volume * generator_function(t, frequency)
        if t < ramp:
            return t / ramp * value
        elif t > duration - ramp:
            return (duration - t) / ramp * value
        else:
            return value

    samples = [modulation(index / sample_rate) if index < sample_count else 0 for index in range(sample_count + trailing_samples)]

    with speaker_output_lock:
        offset = 0
        while offset < len(samples):
            pcm_buffer = bytearray(period_size * 2)
            for index, sample in enumerate(samples[offset : offset + period_size]):
                word = min(max(int(sample * 32768), -32768), 32767)
                pcm_buffer[2 * index] = word % 256
                pcm_buffer[2 * index + 1] = (word >> 8) % 256
            speaker_output_stream.write(pcm_buffer)
            offset += period_size
            with global_phase_lock:
                if global_phase != start_phase:
                    break


recording_lock = threading.Lock()
recording_process = None


def microphone_start_recording(microphone, relative_or_absolute_path):
    global recording_process
    from subprocess import DEVNULL
    from pathlib import Path
    desktop = os.path.expanduser('~/Desktop')
    path = str(Path(desktop) / Path(relative_or_absolute_path))  # If given relative path combines it with ~/Desktop prefix.
    if not isinstance(path, str) or not (path.endswith('.wav') or path.endswith('.mp3')):
        raise ValueError('Path does not end with .wav or .mp3')
    with recording_lock:
        if recording_process is not None:
            recording_process.terminate()
            recording_process = None
        command = ['ffmpeg', '-hide_banner', '-nostats', '-y', '-f', 'pulse', '-i', 'default', path]
        recording_process = subprocess.Popen(command, stdin=DEVNULL, stdout=DEVNULL, stderr=DEVNULL)


def microphone_stop_recording(microphone):
    global recording_process
    with recording_lock:
        if recording_process is not None:
            recording_process.terminate()
            recording_process = None


voice_lock = threading.Lock()
voice_active = False
voice_command_queue = None
vosk_stream = None
precise_runner = None

def microphone_start_voice_recognition(microphone):
    global voice_active, voice_command_queue, vosk_stream, precise_runner
    import os, gc, queue, json, threading, alsaaudio
    from vosk import Model, KaldiRecognizer
    from precise_runner import PreciseEngine, PreciseRunner  # Note: Seemingly precise-engine wants N samples, not N bytes (as documented)

    with voice_lock:
        if not voice_active:

            COMMAND_DURATION = 64

            class Stream:

                QUEUE_DEPTH = 64
                CHUNK_SIZE = 2048
                POISON = object()

                def __init__(self):
                    self.queue = queue.Queue(self.QUEUE_DEPTH)
                    self.terminated_lock = threading.Lock()
                    self.terminated = False

                def write(self, chunk):
                    with self.terminated_lock:
                        if self.terminated:
                            return
                    assert(len(chunk) == self.CHUNK_SIZE)
                    self.queue.put(chunk)

                def read(self, size):
                    assert(size == self.CHUNK_SIZE)
                    chunk = self.queue.get()
                    if chunk is Stream.POISON:
                        with self.terminated_lock:
                            assert self.terminated
                    with self.terminated_lock:
                        if self.terminated:
                            return None
                        return bytes(chunk)

                def clear(self):
                    with self.terminated_lock:
                        if self.terminated:
                            return
                    try:
                        while True:
                            self.queue.get(block=False)
                    except queue.Empty:
                        pass

                def terminate(self):
                    with self.terminated_lock:
                        self.terminated = True
                    self.queue.put(Stream.POISON)

            wake_stream = Stream()
            vosk_stream = Stream()
            vosk_restart = False
            home = os.path.expanduser('~')
            vosk_model = Model(os.path.join(home, 'AbstractFoundry', 'Daemon', 'Voice', 'vosk-model-small-en-us-0.15'))
            precise_engine = os.path.join(home, 'AbstractFoundry', 'Daemon', 'Voice', 'precise-engine', 'precise-engine')
            precise_model = os.path.join(home, 'AbstractFoundry', 'Daemon', 'Voice', 'hey-mycroft.pb')
            command_queue = queue.Queue(6)
            timeout = 0

            capture = alsaaudio.PCM(alsaaudio.PCM_CAPTURE,
                channels=1,
                rate=16000,
                format=alsaaudio.PCM_FORMAT_S16_LE,
                periodsize=2048  # Doesn't actually work.
            )

            def vosk_thread_function():  # TODO: We should periodically rebuild the recognizer even if AcceptWaveform never returns true.
                nonlocal vosk_restart
                try:
                    recognizer = None

                    def rebuild_recognizer():
                        nonlocal recognizer
                        if recognizer is not None:
                            del recognizer
                        recognizer = KaldiRecognizer(vosk_model, 16000)
                    count = 0
                    rebuild_recognizer()
                    while True:
                        data = vosk_stream.read(Stream.CHUNK_SIZE)
                        if data is None:  # End of stream.
                            if recognizer is not None:
                                recognizer.FinalResult()  # Causes the recognizer to free some memory.
                                del recognizer
                                gc.collect()
                            break
                        if vosk_restart:
                            vosk_restart = False
                            recognizer.Result()  # Causes the recognizer to disregard previous input.
                        if recognizer.AcceptWaveform(data):
                            result = json.loads(recognizer.Result())
                            command_queue.put(result['text'])
                            count += 1
                            if count > 4:
                                recognizer.FinalResult()  # Causes the recognizer to free some memory.
                                rebuild_recognizer()
                                gc.collect()
                                count = 0
                except (SystemExit, KeyboardInterrupt):
                    return
                except Exception:
                    print(traceback.format_exc(), flush=True)

            vosk_thread = threading.Thread(target=vosk_thread_function, daemon=True)

            def stream_thread_function():
                nonlocal timeout
                try:
                    samples = bytearray()
                    while True:
                        length, data = capture.read()
                        if not voice_active:
                            break
                        if length > 0:
                            samples.extend(data)
                            while len(samples) >= Stream.CHUNK_SIZE:
                                head = samples[:Stream.CHUNK_SIZE]
                                samples = samples[Stream.CHUNK_SIZE:]
                                wake_stream.write(head)
                                if timeout > 0:
                                    vosk_stream.write(head)
                                    timeout = timeout - 1
                except (SystemExit, KeyboardInterrupt):
                    return
                except Exception:
                    print(traceback.format_exc(), flush=True)

            stream_thread = threading.Thread(target=stream_thread_function, daemon=True)

            def accept_command():
                nonlocal vosk_restart, timeout
                speaker_say(None, 'yes')
                vosk_stream.clear()
                vosk_restart = True
                timeout = COMMAND_DURATION

            def on_activation():
                try:  # Note: Don't allow exceptions to bubble back into the PreciseRunner library.
                    accept_command()
                except Exception:
                    print(traceback.format_exc(), flush=True)

            precise_runner = PreciseRunner(PreciseEngine(precise_engine, precise_model, chunk_size=Stream.CHUNK_SIZE),
                                           stream=wake_stream,
                                           on_activation=on_activation)

            voice_command_queue = command_queue
            voice_active = True
            vosk_thread.start()  # Start threads only after setting voice_active to True.
            stream_thread.start()
            precise_runner.start()

    speaker_say(None, 'at your service')


def microphone_wait_for_sentence(microphone, timeout=None):  # TODO: This could block forever, and the client could have disappeared, using up one of Waitress' thread indefinitely, fix this issue, e.g. by polling every 50 ms like get_next_action / get_next_gesture.
    import queue
    with voice_lock:
        if not voice_active:
            raise RuntimeError('Voice recognition has not been started, call start_voice_recognition() first')
        else:
            command_queue = voice_command_queue
            assert command_queue is not None
    try:
        return command_queue.get(block=True, timeout=timeout)
    except queue.Empty:
        return None


def microphone_stop_voice_recognition(microphone):
    global voice_active, voice_command_queue, vosk_stream, precise_runner
    with voice_lock:
        if voice_active:
            vosk_stream.terminate()
            precise_runner.stop()
            vosk_stream, precise_runner = None, None
            voice_active = False
            voice_command_queue = None


frequency_analysis_capture = None


def microphone_start_recording_for_frequency_analysis(microphone, sample_rate=8000):
    global frequency_analysis_capture
    if frequency_analysis_capture is None:
        import alsaaudio
        frequency_analysis_capture = alsaaudio.PCM(alsaaudio.PCM_CAPTURE,
            channels=1,
            rate=sample_rate,
            format=alsaaudio.PCM_FORMAT_S16_LE,
            periodsize=2048  # Doesn't actually work.
        )


def microphone_get_frequency_buckets(microphone, num_buckets=8, min_hz=0, max_hz=4000, sample_rate=8000):
    import numpy
    if max_hz * 2 > sample_rate:
        raise Exception("Max frequency is too high for the given sample rate. Increase the sample rate.")
    resolution_hz = (max_hz - min_hz) / num_buckets
    num_samples = int(sample_rate / resolution_hz)
    num_repeats = 64 / num_samples  # Ensure we use at least 64 samples.
    buckets = {}
    while num_repeats > 0:
        # Block until we have enough samples.
        data_buffer = bytearray()
        while len(data_buffer) < num_samples*2:
            time.sleep(0.001)
            length, data = frequency_analysis_capture.read()
            if length > 0:
                data_buffer.extend(data)
            elif length < 0:
                # It takes a few reads before the microphone warms up.
                frequency_analysis_capture.read()
                frequency_analysis_capture.read()
        samples = []
        for i in range(0, num_samples):
            samples.append(int.from_bytes(data_buffer[2*i:2*i+2], byteorder='little', signed=True))
        magnitudes = numpy.absolute(numpy.fft.fft(samples))
        frequencies = numpy.fft.fftfreq(num_samples, 1/sample_rate)
        for i in range(0, int(num_samples/2)):
            freq = frequencies[i]
            if min_hz <= frequencies[i] < max_hz:
                if freq not in buckets:
                    buckets[freq] = 0
                buckets[freq] += magnitudes[i]
        num_repeats -= 1
    return buckets


def pi_ip_address():
    import psutil
    preferred = 'wlan0'
    interfaces = psutil.net_if_addrs()
    names = [name for name in interfaces if name.startswith('wl')]  # Wireless interfaces typically start with 'wl'.
    if len(names) < 1:
        address = ''
    elif preferred in names:
        address = interfaces[preferred][0][1]  # AddressFamily.AF_INET.
    else:
        address = interfaces[names[0]][0][1]  # AddressFamily.AF_INET.
    return address if ':' not in address else ''


def pi_cpu_temp():
    import psutil
    temperatures = psutil.sensors_temperatures()
    if 'cpu_thermal' in temperatures:
        return temperatures['cpu_thermal'][0].current
    elif 'coretemp' in temperatures:
        return temperatures['coretemp'][0].current
    else:
        return 0


def pi_cpu_percent():
    import psutil
    return psutil.cpu_percent()


def pi_ram_percent_used():
    import psutil
    total = psutil.virtual_memory().total/(1024*1024)
    used = psutil.virtual_memory().used/(1024*1024)
    return used * 100.0 / total


def pi_disk_percent():
    import psutil
    return psutil.disk_usage("/").percent


def start():
    service = Flask(__name__)
    install_endpoints(service)
    try:
        os.remove(SOCKET_PATH)
    except FileNotFoundError:
        pass  # Safely ignored.
    waitress.serve(service, unix_socket=SOCKET_PATH, threads=6)
