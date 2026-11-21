# Third-Party Notices

## dbus-java

The Linux MPRIS backend uses dbus-java 5.0.0:

- `com.github.hypfvieh:dbus-java-core`
- `com.github.hypfvieh:dbus-java-transport-native-unixsocket`

Copyright the dbus-java contributors. Licensed under the Apache License, Version 2.0.
Project: https://github.com/hypfvieh/dbus-java

The JDK Unix-domain-socket transport classes are flattened into the mod jar at build time
(see `build.gradle`, `mprisTransport`) so that the provider is visible under NeoForge's
nested-library loading. The Apache 2.0 license text is available at:

https://www.apache.org/licenses/LICENSE-2.0
