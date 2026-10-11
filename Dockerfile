# Reproducible Android build environment: same JDK, Gradle and SDK as CI
# (.github/workflows/android-build.yml), so every PC builds identically.
FROM gradle:8.14.3-jdk17

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle-cache
ENV PATH="$PATH:/opt/android-sdk/cmdline-tools/latest/bin"

USER root
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Pinned command-line tools (11076708 = 12.0)
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q /tmp/cmdline.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/cmdline.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" > /dev/null

COPY docker/build.sh /usr/local/bin/build-app
# Strip CRs in case Git on Windows checked the script out with CRLF.
RUN sed -i 's/\r$//' /usr/local/bin/build-app && chmod +x /usr/local/bin/build-app \
    && mkdir -p /gradle-cache && chmod 777 /gradle-cache

WORKDIR /workspace/android
ENTRYPOINT ["build-app"] 


