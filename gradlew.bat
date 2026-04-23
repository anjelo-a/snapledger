@ECHO OFF
SET DIR=%~dp0
SET JAR=%DIR%\gradle\wrapper\gradle-wrapper.jar
IF NOT EXIST "%JAR%" (
  ECHO gradle-wrapper.jar is missing. Open in Android Studio and let it configure Gradle, or generate wrapper locally.
  EXIT /B 1
)
java -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
