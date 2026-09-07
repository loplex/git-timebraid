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
@rem Environment:
@rem     JAVA_HOME   the JVM to use; `java` from PATH when unset
@rem     JAVA_OPTS   passed to the JVM, typically a larger heap for a large graph
@rem

setlocal

set "TIMEBRAID_HOME=%~dp0.."
set "TIMEBRAID_JAR=%TIMEBRAID_HOME%\lib\git-timebraid.jar"

if not exist "%TIMEBRAID_JAR%" (
    echo git-timebraid: jar not found at %TIMEBRAID_JAR% 1>&2
    echo git-timebraid: expected bin\ and lib\ side by side, as laid out by the distribution archive 1>&2
    exit /b 1
)

if defined JAVA_HOME (
    set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_CMD=java"
)

@rem JAVA_OPTS is deliberately unquoted: it holds several JVM options and has to split.
"%JAVA_CMD%" %JAVA_OPTS% -jar "%TIMEBRAID_JAR%" %*
exit /b %ERRORLEVEL%
