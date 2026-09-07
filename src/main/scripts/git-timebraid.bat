@echo off
@rem
@rem git-timebraid launcher.
@rem
@rem Expects the layout of the distribution archive:
@rem
@rem     bin\git-timebraid.bat   <- this script
@rem     lib\git-timebraid.jar
@rem
@rem Put bin\ on PATH and both `git-timebraid ...` and `git timebraid ...` work, the latter because
@rem git runs any `git-<name>` it finds on PATH as a subcommand.
@rem
@rem The JVM is looked for in this order, first hit wins:
@rem
@rem     %TIMEBRAID_JAVA%       an explicit java executable
@rem     <install>\runtime\     the bundled runtime, present only in the platform-specific archive
@rem     %JAVA_HOME%\bin\java.exe
@rem     java on PATH
@rem
@rem The bundled runtime outranks JAVA_HOME on purpose: the point of that archive is to run on a
@rem machine whose Java is absent, old, or not the one this was tested against. TIMEBRAID_JAVA is
@rem the way out when you do want your own.
@rem
@rem Environment:
@rem     TIMEBRAID_JAVA   java executable to run, overriding everything below it
@rem     JAVA_HOME        the JVM to use when there is no bundled runtime
@rem     JAVA_OPTS        passed to the JVM, typically a larger heap for a large graph
@rem

setlocal

set "TIMEBRAID_HOME=%~dp0.."
set "TIMEBRAID_JAR=%TIMEBRAID_HOME%\lib\git-timebraid.jar"

if not exist "%TIMEBRAID_JAR%" (
    echo git-timebraid: jar not found at %TIMEBRAID_JAR% 1>&2
    echo git-timebraid: expected bin\ and lib\ side by side, as laid out by the distribution archive 1>&2
    exit /b 1
)

if defined TIMEBRAID_JAVA (
    set "JAVA_CMD=%TIMEBRAID_JAVA%"
) else if exist "%TIMEBRAID_HOME%\runtime\bin\java.exe" (
    set "JAVA_CMD=%TIMEBRAID_HOME%\runtime\bin\java.exe"
) else if defined JAVA_HOME (
    set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_CMD=java"
)

@rem JAVA_OPTS is deliberately unquoted: it holds several JVM options and has to split.
"%JAVA_CMD%" %JAVA_OPTS% -jar "%TIMEBRAID_JAR%" %*
exit /b %ERRORLEVEL%
