#!/usr/bin/python3

# To install this software using the latest version of the installer please run:
# python3 <(curl -fL https://www.abstractfoundry.com/lumicube/download/install.py)

import os
import platform
import shlex
import shutil
import stat
import subprocess
import urllib.request

DOWNLOAD_URL_PREFIX = 'https://www.abstractfoundry.com/lumicube/download/'

if os.geteuid() == 0:
    raise RuntimeError('Run this script as your own user, not as root / sudo.')


def run(command, data=None, check=True, quiet=False):
    subprocess.run(command, shell=True, check=check,
                   input=data, text=isinstance(data, str),
                   stdout=subprocess.DEVNULL if quiet else None,
                   stderr=subprocess.DEVNULL if quiet else None)


def set_default_executable_permissions(path):
    os.chmod(path,
             stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH |
             stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH |
             stat.S_IWUSR)


def platform_variant():
    return 'arm' if 'arm' in platform.machine() else 'x64'


def install_system_dependencies():
    print('Installing system dependencies...')

    apt_packages = ['curl', 'unzip', 'libfuse2', 'iptables', 'python3', 'python3-pip']
    pip_packages = ['flask==2.1.2', 'waitress==2.1.1']
    run('sudo DEBIAN_FRONTEND=noninteractive apt update')
    run('sudo DEBIAN_FRONTEND=noninteractive apt install -y ' + ' '.join(apt_packages))
    run('yes 2>/dev/null | python3 -m pip install --upgrade pip')
    run('yes 2>/dev/null | python3 -m pip install ' + ' '.join(pip_packages))


def configure_serial_port():
    print('Configuring the serial interface...')

    if shutil.which('raspi-config') is not None:
        run('sudo raspi-config nonint do_serial 2')  # Enable UART, but disable serial login shell.
        run('sudo bash', '''
if ! grep -q ^dtoverlay=miniuart-bt /boot/config.txt;
then
    echo dtoverlay=miniuart-bt >> /boot/config.txt;
fi
''')  # Switch the Bluetooth peripheral over to the Mini-UART, so we can use the PL011 UART.
        run('sudo raspi-config nonint set_config_var core_freq 250 /boot/config.txt')  # Fix the Mini-UART frequency.


def install_daemon():
    print('Installing the Abstract Foundry Daemon...')

    home = os.path.expanduser('~')
    daemon_directory = os.path.join(home, 'AbstractFoundry', 'Daemon')
    os.makedirs(daemon_directory, exist_ok=True)
    software_directory = os.path.join(daemon_directory, 'Software')
    os.makedirs(software_directory, exist_ok=True)
    service_directory = os.path.join(home, '.local', 'share', 'systemd', 'user')
    os.makedirs(service_directory, exist_ok=True)
    variant = platform_variant()

    run('systemctl --user stop foundry-daemon.service', check=False, quiet=True)
    run('systemctl --user disable foundry-daemon.service', check=False, quiet=True)

    version_url = DOWNLOAD_URL_PREFIX + 'latest_daemon.txt'
    with urllib.request.urlopen(version_url) as file:
        version = file.read().decode('utf-8').strip()

    daemon_name = 'Daemon-' + version + '-' + variant + '.AppImage'
    binary_url = DOWNLOAD_URL_PREFIX + daemon_name
    destination_path = os.path.join(software_directory, daemon_name)
    run('curl -fL ' + shlex.quote(binary_url) + ' > ' + destination_path)
    set_default_executable_permissions(destination_path)

    launch_path = os.path.join(daemon_directory, 'launch.sh')
    with open(launch_path, 'w') as file:
        file.write('''#!/bin/bash
CONTAINING_DIRECTORY="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
"$CONTAINING_DIRECTORY"/Software/Daemon-"$( cat "$CONTAINING_DIRECTORY"/version.txt )"-''' + variant + '''.AppImage "$@"
''')
    set_default_executable_permissions(launch_path)

    version_path = os.path.join(daemon_directory, 'version.txt')
    with open(version_path, 'w') as file:
        file.write(version)

    launch_path = os.path.join(service_directory, 'foundry-daemon.service')
    with open(launch_path, 'w') as file:
        file.write('''[Unit]
Description=Abstract Foundry Daemon
After=network.target pulseaudio.target
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
ExecStartPre=/bin/sleep 5
ExecStart=%h/AbstractFoundry/Daemon/launch.sh /dev/ttyAMA0
WorkingDirectory=%h/AbstractFoundry/Daemon
Restart=always
RestartSec=5s

[Install]
WantedBy=default.target
''')
    run('systemctl --user enable foundry-daemon.service')


def configure_iptables_rules():
    print('Configuring iptables rules...')

    run('sudo systemctl stop foundry-redirect.service', check=False, quiet=True)
    run('sudo systemctl disable foundry-redirect.service', check=False, quiet=True)

    rules = [
        'iptables -t nat -A PREROUTING -p tcp --dport 80 -m addrtype --dst-type LOCAL -j REDIRECT --to-ports 8686',
        'iptables -t nat -A OUTPUT -o lo -p tcp --dport 80 -m addrtype --dst-type LOCAL -j REDIRECT --to-ports 8686',
        'ip6tables -t nat -A PREROUTING -p tcp --dport 80 -m addrtype --dst-type LOCAL -j REDIRECT --to-ports 8686',
        'ip6tables -t nat -A OUTPUT -o lo -p tcp --dport 80 -m addrtype --dst-type LOCAL -j REDIRECT --to-ports 8686'
    ]

    run('sudo bash', '''
cat > /etc/systemd/system/foundry-redirect.service <<\\EOF
[Unit]
Description=Abstract Foundry Port Redirection
After=network.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/bash -c "''' + ';'.join(rules) + '''"

[Install]
WantedBy=multi-user.target
EOF
''')
    run('sudo systemctl enable foundry-redirect.service')


def install_addon_packages():
    print('Installing addon packages...')

    apt_packages = ['python3-cffi', 'python3-numpy', 'python3-requests', 'python3-matplotlib', 'ffmpeg', 'espeak']
    pip_packages = ['pyalsaaudio==0.9.0', 'pyttsx3==2.90', 'precise-runner==0.3.1', 'vosk==0.3.32']
    run('sudo DEBIAN_FRONTEND=noninteractive apt install -y ' + ' '.join(apt_packages))
    run('yes 2>/dev/null | python3 -m pip install ' + ' '.join(pip_packages))

    home = os.path.expanduser('~')
    voice_directory = os.path.join(home, 'AbstractFoundry', 'Daemon', 'Voice')
    os.makedirs(voice_directory, exist_ok=True)
    desktop_directory = os.path.join(home, 'Desktop')
    os.makedirs(desktop_directory, exist_ok=True)

    precise_variant = 'armv7l' if platform_variant() == 'arm' else 'x86_64'

    vosk_model = 'vosk-model-small-en-us-0.15.zip'
    vosk_model_path = os.path.join(voice_directory, vosk_model)
    run('curl -fL '
        + shlex.quote('https://alphacephei.com/vosk/models/' + vosk_model)
        + ' > ' + vosk_model_path)

    precise_engine = 'precise-engine_0.3.0_' + precise_variant + '.tar.gz'
    precise_engine_path = os.path.join(voice_directory, precise_engine)
    run('curl -fL '
        + shlex.quote('https://github.com/MycroftAI/mycroft-precise/releases/download/v0.3.0/' + precise_engine)
        + ' > ' + precise_engine_path)

    mycroft_model = 'hey-mycroft.pb'
    mycroft_model_path = os.path.join(voice_directory, mycroft_model)
    run('curl -fL '
        + shlex.quote('https://github.com/MycroftAI/precise-data/raw/models/' + mycroft_model)
        + ' > ' + mycroft_model_path)

    mycroft_params = 'hey-mycroft.pb.params'
    mycroft_params_path = os.path.join(voice_directory, mycroft_params)
    run('curl -fL '
        + shlex.quote('https://github.com/MycroftAI/precise-data/raw/models/' + mycroft_params)
        + ' > ' + mycroft_params_path)

    run('unzip -o ' + vosk_model_path + ' -d ' + voice_directory)
    run('tar -zxvf ' + precise_engine_path + ' -C ' + voice_directory)

    autumn_image = 'autumn.jpg'
    autumn_image_path = os.path.join(desktop_directory, autumn_image)
    run('curl -fL '
        + shlex.quote(DOWNLOAD_URL_PREFIX + autumn_image)
        + ' > ' + autumn_image_path)


install_system_dependencies()
configure_serial_port()
install_daemon()
configure_iptables_rules()
install_addon_packages()

print('----------------------')
print('Installation complete.')
print('----------------------')
input('Press enter to reboot your system now...\n')
run('sudo /usr/sbin/reboot')
