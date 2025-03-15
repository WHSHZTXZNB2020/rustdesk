Got dependencies!
146 packages have newer versions incompatible with dependency constraints.
Try `flutter pub outdated` for more information.

Running Gradle task 'assembleRelease'...                        
Checking the license for package Android SDK Build-Tools 30.0.3 in /usr/local/lib/android/sdk/licenses
License for package Android SDK Build-Tools 30.0.3 accepted.
Preparing "Install Android SDK Build-Tools 30.0.3 (revision: 30.0.3)".
"Install Android SDK Build-Tools 30.0.3 (revision: 30.0.3)" ready.
Installing Android SDK Build-Tools 30.0.3 in /usr/local/lib/android/sdk/build-tools/30.0.3
"Install Android SDK Build-Tools 30.0.3 (revision: 30.0.3)" complete.
"Install Android SDK Build-Tools 30.0.3 (revision: 30.0.3)" finished.
Expected to find fonts for (MaterialIcons, AddressBook, DeviceGroup, ..., gestureicons, packages/cupertino_icons/CupertinoIcons), but found (MaterialIcons, Tabbar, AddressBook, DeviceGroup). This usually means you are referring to font families in an IconData class but not including them in the assets section of your pubspec.yaml, are missing the package that would include them, or are missing "uses-material-design: true".
Font asset "MaterialIcons-Regular.otf" was tree-shaken, reducing it from 1645184 to 19732 bytes (98.8% reduction). Tree-shaking can be disabled by providing the --no-tree-shake-icons flag when building your app.
Note: /home/runner/.pub-cache/git/uni_links-f416118d843a7e9ed117c7bb7bdc2deda5a9e86f/uni_links/android/src/main/java/name/avioli/unilinks/UniLinksPlugin.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
e: file:///home/runner/work/rustdesk/rustdesk/flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt:576:9 Unresolved reference: mediaProjection
e: file:///home/runner/work/rustdesk/rustdesk/flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt:642:13 Unresolved reference: requestMediaProjection

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compileReleaseKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.

* Get more help at https://help.gradle.org

BUILD FAILED in 4m
Running Gradle task 'assembleRelease'...                          241.2s
Gradle task assembleRelease failed with exit code 1
Error: Process completed with exit code 1.
