#!/bin/bash

if [ $EUID -ne 0 ]
then
	echo "This script requires root permissions, please run under sudo."
	exit
fi

if [ $# -eq 0 ]
then
	echo "Usage: sudo $0 Daemon-1.2.3"
	echo "This script packages Daemon-1.2.3.zip located in the current working directory as an AppImage, producing Daemon-1.2.3-x64.AppImage, Daemon-1.2.3-arm.AppImage etc. for each supported platform."
	echo "TODO: In the future this script should also build the Daemon archive from source."
	exit
fi

if [ ! -e $1.zip ]
then
	echo "$1.zip does not exist."
	exit
fi

context=$(mktemp -d)

echo $1 > $context/daemon.name
cp $1.zip $context/

cat > $context/AppRun <<\EOF
#!/bin/bash
"$APPDIR/usr/lib/jvm/foundry-default/bin/java" -classpath "$APPDIR/opt/abstractfoundry/daemon/lib/*" -Dlogback.configurationFile="$APPDIR/opt/abstractfoundry/daemon/log.xml" com.abstractfoundry.daemon.Daemon "$@"
EOF

cat > $context/Daemon.desktop <<\EOF
[Desktop Entry]
Name=Daemon
Exec=Daemon
Icon=Daemon
Type=Application
Categories=Utility;
EOF

cat > $context/Dockerfile <<\EOF

FROM debian:buster-20220228
# TODO: Use the base image "maven:3.8.5-eclipse-temurin-16" to automatically build the Daemon project inside Docker.

RUN apt update && apt install -y file wget unzip

RUN mkdir -p /root/Downloads /root/java/x64 /root/java/arm

WORKDIR /root/Downloads
RUN wget 'https://github.com/adoptium/temurin16-binaries/releases/download/jdk-16.0.2+7/OpenJDK16U-jdk_x64_linux_hotspot_16.0.2_7.tar.gz'
#RUN wget 'https://github.com/adoptium/temurin16-binaries/releases/download/jdk-16.0.2+7/OpenJDK16U-jre_x64_linux_hotspot_16.0.2_7.tar.gz'
RUN wget 'https://github.com/adoptium/temurin16-binaries/releases/download/jdk-16.0.2+7/OpenJDK16U-jdk_arm_linux_hotspot_16.0.2_7.tar.gz'
#RUN wget 'https://github.com/adoptium/temurin16-binaries/releases/download/jdk-16.0.2+7/OpenJDK16U-jre_arm_linux_hotspot_16.0.2_7.tar.gz'
RUN tar -xf OpenJDK16U-jdk_x64_linux_hotspot_16.0.2_7.tar.gz -C /root/java/x64
#RUN tar -xf OpenJDK16U-jre_x64_linux_hotspot_16.0.2_7.tar.gz -C /root/java/x64
RUN tar -xf OpenJDK16U-jdk_arm_linux_hotspot_16.0.2_7.tar.gz -C /root/java/arm
#RUN tar -xf OpenJDK16U-jre_arm_linux_hotspot_16.0.2_7.tar.gz -C /root/java/arm
RUN wget 'https://github.com/AppImage/AppImageKit/releases/download/13/appimagetool-x86_64.AppImage'
RUN wget 'https://github.com/AppImage/AppImageKit/releases/download/13/runtime-x86_64'
RUN wget 'https://github.com/AppImage/AppImageKit/releases/download/13/runtime-armhf'
RUN chmod a+x appimagetool-x86_64.AppImage

WORKDIR /root
RUN echo '#!/bin/sh' >> appimagetool
RUN echo '/root/Downloads/appimagetool-x86_64.AppImage --appimage-extract-and-run "$@"' >> appimagetool
RUN chmod a+x appimagetool
RUN cp /root/Downloads/runtime-x86_64 /root/runtime-x64
RUN cp /root/Downloads/runtime-armhf /root/runtime-arm

EOF

cat > $context/script.sh <<\EOF

#!/bin/bash

DAEMON_NAME=`cat /mnt/context/daemon.name`
echo "Building $DAEMON_NAME..."

cd /root
mkdir -p /mnt/context/images
for PLATFORM in "x64" "arm"
do
	APP_DIR=/root/build/$PLATFORM/AppDir
	mkdir -p $APP_DIR
	cp /mnt/context/AppRun $APP_DIR/
	cp /mnt/context/Daemon.desktop $APP_DIR/
	touch $APP_DIR/Daemon.png
	chmod a+x $APP_DIR/AppRun
	mkdir -p $APP_DIR/usr/lib/jvm
	mv /root/java/$PLATFORM/jdk-16.0.2+7/ $APP_DIR/usr/lib/jvm/foundry-default
	mkdir -p $APP_DIR/opt/abstractfoundry
	unzip /mnt/context/$DAEMON_NAME.zip -d /root/build/$PLATFORM
	mv /root/build/$PLATFORM/$DAEMON_NAME $APP_DIR/opt/abstractfoundry/daemon
	/root/appimagetool --runtime-file runtime-$PLATFORM $APP_DIR
	mv /root/*.AppImage /mnt/context/images/$DAEMON_NAME-$PLATFORM.AppImage
done

EOF

docker build -t daemon-build-environment $context/
sudo docker run --rm -v $context:/mnt/context -i daemon-build-environment /bin/bash < $context/script.sh

cp $context/images/* .
