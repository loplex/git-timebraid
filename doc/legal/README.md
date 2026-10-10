# Third-party licenses

Every archive carries the program's dependencies in `lib/`,
each as the unmodified jar its project publishes, beside `lib/git-timebraid.jar`.\
Each is listed below under the license it is used under,
and the full text of every one of those licenses is in this directory.\
A jar that ships license files of its own still carries them.

`.github/scripts/check-legal.py` holds the list to the build's runtime dependencies, both ways:
a dependency added without a row here, or a row left behind by one removed, fails CI.

## Libraries

| Library                                              | License                               | Project                                          |
|------------------------------------------------------|---------------------------------------|--------------------------------------------------|
| `org.eclipse.jgit:org.eclipse.jgit`                  | [EDL 1.0](EDL-1.0.txt)                | https://www.eclipse.org/jgit/                    |
| `org.slf4j:slf4j-api`                                | [MIT](MIT.txt)                        | https://www.slf4j.org/                           |
| `org.slf4j:slf4j-simple`                             | [MIT](MIT.txt)                        | https://www.slf4j.org/                           |
| `com.github.ajalt.colormath:colormath-jvm`           | [MIT](MIT.txt)                        | https://github.com/ajalt/colormath               |
| `com.github.ajalt.clikt:clikt-jvm`                   | [Apache 2.0](Apache-2.0.txt)          | https://github.com/ajalt/clikt                   |
| `com.github.ajalt.clikt:clikt-core-jvm`              | [Apache 2.0](Apache-2.0.txt)          | https://github.com/ajalt/clikt                   |
| `com.github.ajalt.mordant:mordant-jvm`               | [Apache 2.0](Apache-2.0.txt)          | https://github.com/ajalt/mordant                 |
| `com.github.ajalt.mordant:mordant-core-jvm`          | [Apache 2.0](Apache-2.0.txt)          | https://github.com/ajalt/mordant                 |
| `com.github.ajalt.mordant:mordant-jvm-ffm-jvm`       | [Apache 2.0](Apache-2.0.txt)          | https://github.com/ajalt/mordant                 |
| `com.github.ajalt.mordant:mordant-jvm-graal-ffi-jvm` | [Apache 2.0](Apache-2.0.txt)          | https://github.com/ajalt/mordant                 |
| `com.github.ajalt.mordant:mordant-jvm-jna-jvm`       | [Apache 2.0](Apache-2.0.txt)          | https://github.com/ajalt/mordant                 |
| `com.googlecode.javaewah:JavaEWAH`                   | [Apache 2.0](Apache-2.0.txt)          | https://github.com/lemire/javaewah               |
| `commons-codec:commons-codec`                        | [Apache 2.0](Apache-2.0.txt)          | https://commons.apache.org/proper/commons-codec/ |
| `org.jetbrains.kotlin:kotlin-stdlib`                 | [Apache 2.0](Apache-2.0.txt)          | https://kotlinlang.org/                          |
| `org.jetbrains:annotations`                          | [Apache 2.0](Apache-2.0.txt)          | https://github.com/JetBrains/java-annotations    |
| `net.java.dev.jna:jna`                               | [Apache 2.0](Apache-2.0.txt), elected | https://github.com/java-native-access/jna        |

## Notices

The MIT License and the EDL ask for each library's own copyright notice to travel with it;
the Apache License 2.0 asks the same of a NOTICE file a library ships.

- **JGit**: Copyright (c) 2007, Eclipse Foundation, Inc. and its licensors.\
  JGit includes SHA-1 UbcCheck under the [MIT License](MIT.txt): Copyright (c) 2017 Marc Stevens,
  Cryptology Group, Centrum Wiskunde & Informatica, and Dan Shumow, Microsoft Research.\
  Both notices are in `about.html` inside JGit's jar.
- **slf4j-api, slf4j-simple**: Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland). All rights reserved.
- **colormath**: Copyright 2021 AJ Alt.
- **commons-codec**, its NOTICE: "Apache Commons Codec.
  Copyright 2002-2026 The Apache Software Foundation.
  This product includes software developed at The Apache Software Foundation (https://www.apache.org/)."
- **JNA** is offered under either the Apache License 2.0 or the [LGPL 2.1](LGPL-2.1.txt) or later,
  and is used here under the Apache License 2.0.\
  Its jar carries both texts, as `META-INF/AL2.0` and `META-INF/LGPL2.1`.

## The bundled runtime

The archives whose name carries an operating system and an architecture
also hold a trimmed OpenJDK build under `runtime/`,
licensed under the GNU General Public License, version 2, with the OpenJDK Assembly Exception.\
Its own license files travel with it, under `runtime/legal/`, one directory per module.
