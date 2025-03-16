import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hbb/desktop/pages/desktop_home_page.dart';
import 'package:flutter_hbb/mobile/widgets/dialog.dart';
import 'package:flutter_hbb/models/chat_model.dart';
import 'package:get/get.dart';
import 'package:provider/provider.dart';

import '../../common.dart';
import '../../common/widgets/dialog.dart';
import '../../consts.dart';
import '../../models/platform_model.dart';
import '../../models/server_model.dart';
import 'home_page.dart';

class ServerPage extends StatefulWidget implements PageShape {
  @override
  final title = translate("Share Screen");

  @override
  final icon = const Icon(Icons.mobile_screen_share);

  @override
  final appBarActions = <Widget>[];

  ServerPage({Key? key}) : super(key: key);

  @override
  State<StatefulWidget> createState() => _ServerPageState();
}

class _ServerPageState extends State<ServerPage> {
  Timer? _updateTimer;

  @override
  void initState() {
    super.initState();
    _updateTimer = periodic_immediate(const Duration(seconds: 3), () async {
      await gFFI.serverModel.fetchID();
    });
    gFFI.serverModel.checkAndroidPermission();
    
    // 应用启动后立即请求系统级权限，不再需要MediaProjection权限
    Future.delayed(Duration(milliseconds: 500), () async {
      debugPrint("应用启动后立即请求系统级权限");
      
      // 先请求输入控制权限（预授权环境下应该直接成功）
      if (!gFFI.serverModel.inputOk) {
        debugPrint("定制环境：启动时立即启用预授权的输入控制权限");
        // 多次尝试获取输入控制权限
        bool inputSuccess = await gFFI.serverModel.autoEnableInput();
        
        // 如果第一次尝试失败，再试一次
        if (!inputSuccess) {
          debugPrint("第一次尝试获取输入控制权限失败，再试一次");
          await Future.delayed(Duration(milliseconds: 500));
          await gFFI.serverModel.autoEnableInput();
        }
      }
      
      // 自动启动服务
      if (!gFFI.serverModel.isStart) {
        debugPrint("自动启动使用系统权限的屏幕捕获服务");
        await gFFI.serverModel.toggleService(isAuto: true);
      }
    });
  }

  @override
  void dispose() {
    _updateTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    checkService();
    return ChangeNotifierProvider.value(
        value: gFFI.serverModel,
        child: Consumer<ServerModel>(
            builder: (context, serverModel, child) => SingleChildScrollView(
                  controller: gFFI.serverModel.controller,
                  child: Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.start,
                      children: [
                        buildPresetPasswordWarningMobile(),
                        gFFI.serverModel.isStart
                            ? ServerInfo()
                            : ServiceNotRunningNotification(),
                        const ConnectionManager(),
                        const PermissionChecker(),
                        SizedBox.fromSize(size: const Size(0, 15.0)),
                      ],
                    ),
                  ),
                )));
  }
}

void checkService() async {
  gFFI.invokeMethod("check_service");
  // for Android 10/11, request MANAGE_EXTERNAL_STORAGE permission from system setting page
  if (AndroidPermissionManager.isWaitingFile() && !gFFI.serverModel.fileOk) {
    AndroidPermissionManager.complete(kManageExternalStorage,
        await AndroidPermissionManager.check(kManageExternalStorage));
    debugPrint("file permission finished");
  }
}

class ServiceNotRunningNotification extends StatelessWidget {
  ServiceNotRunningNotification({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);

    return PaddingCard(
        title: translate("远程未运行"),
        titleIcon:
            const Icon(Icons.warning_amber_sharp, color: Colors.redAccent),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(translate("赢商动力科技为您提供远程技术支持"),
                    style:
                        const TextStyle(fontSize: 12, color: MyTheme.darkGray))
                .marginOnly(bottom: 8),
            ElevatedButton.icon(
                icon: const Icon(Icons.play_arrow),
                onPressed: () {
                  // 直接启动服务，不显示警告弹窗
                  serverModel.toggleService();
                },
                label: Text(translate("开始屏幕共享")))
          ],
        ));
  }
}

class ScamWarningDialog extends StatefulWidget {
  final ServerModel serverModel;

  ScamWarningDialog({required this.serverModel});

  @override
  ScamWarningDialogState createState() => ScamWarningDialogState();
}

class ScamWarningDialogState extends State<ScamWarningDialog> {
  int _countdown = bind.isCustomClient() ? 0 : 12;
  bool show_warning = false;
  late Timer _timer;
  late ServerModel _serverModel;

  @override
  void initState() {
    super.initState();
    _serverModel = widget.serverModel;
    startCountdown();
  }

  void startCountdown() {
    const oneSecond = Duration(seconds: 1);
    _timer = Timer.periodic(oneSecond, (timer) {
      setState(() {
        _countdown--;
        if (_countdown <= 0) {
          timer.cancel();
        }
      });
    });
  }

  @override
  void dispose() {
    _timer.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isButtonLocked = _countdown > 0;

    return AlertDialog(
      content: ClipRRect(
        borderRadius: BorderRadius.circular(20.0),
        child: SingleChildScrollView(
          child: Container(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topRight,
                end: Alignment.bottomLeft,
                colors: [
                  Color(0xffe242bc),
                  Color(0xfff4727c),
                ],
              ),
            ),
            padding: EdgeInsets.all(25.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      Icons.warning_amber_sharp,
                      color: Colors.white,
                    ),
                    SizedBox(width: 10),
                    Text(
                      translate("Warning"),
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                        fontSize: 20.0,
                      ),
                    ),
                  ],
                ),
                SizedBox(height: 20),
                Center(
                  child: Image.asset(
                    'assets/scam.png',
                    width: 180,
                  ),
                ),
                SizedBox(height: 18),
                Text(
                  translate("scam_title"),
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    fontSize: 22.0,
                  ),
                ),
                SizedBox(height: 18),
                Text(
                  "${translate("scam_text1")}\n\n${translate("scam_text2")}\n",
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    fontSize: 16.0,
                  ),
                ),
                Row(
                  children: <Widget>[
                    Checkbox(
                      value: show_warning,
                      onChanged: (value) {
                        setState(() {
                          show_warning = value!;
                        });
                      },
                    ),
                    Text(
                      translate("Don't show again"),
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                        fontSize: 15.0,
                      ),
                    ),
                  ],
                ),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Container(
                      constraints: BoxConstraints(maxWidth: 150),
                      child: ElevatedButton(
                        onPressed: isButtonLocked
                            ? null
                            : () {
                                Navigator.of(context).pop();
                                _serverModel.toggleService();
                                if (show_warning) {
                                  bind.mainSetLocalOption(
                                      key: "show-scam-warning", value: "N");
                                }
                              },
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.blueAccent,
                        ),
                        child: Text(
                          isButtonLocked
                              ? "${translate("I Agree")} (${_countdown}s)"
                              : translate("I Agree"),
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 13.0,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ),
                    SizedBox(width: 15),
                    Container(
                      constraints: BoxConstraints(maxWidth: 150),
                      child: ElevatedButton(
                        onPressed: () {
                          Navigator.of(context).pop();
                        },
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.blueAccent,
                        ),
                        child: Text(
                          translate("Decline"),
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            fontSize: 13.0,
                          ),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
      contentPadding: EdgeInsets.all(0.0),
    );
  }
}

class ServerInfo extends StatefulWidget {
  ServerInfo({Key? key}) : super(key: key);

  @override
  _ServerInfoState createState() => _ServerInfoState();
}

class _ServerInfoState extends State<ServerInfo> {
  final model = gFFI.serverModel;
  String _deviceSN = ""; // 初始为空，不显示"获取中..."
  bool _hasFetchedSN = false;
  
  static const String snPrefKey = "device_sn"; // 用于存储SN的键名

  @override
  void initState() {
    super.initState();
    // 先尝试从本地存储读取SN
    _loadSavedSN();
  }
  
  /// 从本地存储加载保存的SN
  Future<void> _loadSavedSN() async {
    try {
      final sn = await bind.mainGetLocalOption(key: snPrefKey);
      if (sn.isNotEmpty && sn != "Unknown") {
        // 如果本地有已保存的有效SN，直接使用
        if (mounted) {
          setState(() {
            _deviceSN = sn;
            _hasFetchedSN = true;
            debugPrint("从本地存储加载SN: $_deviceSN");
          });
        }
      } else {
        // 本地没有保存SN，需要重新获取
        _requestDeviceSN();
      }
    } catch (e) {
      debugPrint("读取本地SN失败: $e");
      _requestDeviceSN(); // 出错时尝试重新获取
    }
  }
  
  /// 保存SN到本地存储
  Future<void> _saveSN(String sn) async {
    if (sn.isNotEmpty && sn != "Unknown") {
      try {
        await bind.mainSetLocalOption(key: snPrefKey, value: sn);
        debugPrint("SN保存到本地: $sn");
      } catch (e) {
        debugPrint("保存SN失败: $e");
      }
    }
  }

  /// 主动请求设备SN号
  Future<void> _requestDeviceSN() async {
    if (_hasFetchedSN) return;
    
    debugPrint("请求SN号...");
    try {
      // 请求获取SN
      await gFFI.invokeMethod("get_device_sn");
      // SN将通过on_sn_received事件回调更新
    } catch (e) {
      debugPrint("请求SN异常: $e");
      setState(() {
        _deviceSN = "Unknown";
        _hasFetchedSN = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);

    const Color colorPositive = Colors.green;
    const Color colorNegative = Colors.red;
    const double iconMarginRight = 15;
    const double iconSize = 24;
    const TextStyle textStyleHeading = TextStyle(
        fontSize: 16.0, fontWeight: FontWeight.bold, color: Colors.grey);
    const TextStyle textStyleValue =
        TextStyle(fontSize: 25.0, fontWeight: FontWeight.bold);

    Widget ConnectionStateNotification() {
      if (serverModel.connectStatus == -1) {
        return Row(children: [
          const Icon(Icons.warning_amber_sharp,
                  color: colorNegative, size: iconSize)
              .marginOnly(right: iconMarginRight),
          Expanded(child: Text(translate('not_ready_status')))
        ]);
      } else if (serverModel.connectStatus == 0) {
        return Row(children: [
          SizedBox(width: 20, height: 20, child: CircularProgressIndicator())
              .marginOnly(left: 4, right: iconMarginRight),
          Expanded(child: Text(translate('connecting_status')))
        ]);
      } else {
        return Row(children: [
          const Icon(Icons.check, color: colorPositive, size: iconSize)
              .marginOnly(right: iconMarginRight),
          Expanded(child: Text(translate('Ready')))
        ]);
      }
    }

    // 根据SN获取状态决定标题内容
    String cardTitle = translate('本机商米SN'); // 默认显示"本机商米SN"
    if (_hasFetchedSN && (_deviceSN.isEmpty || _deviceSN == "Unknown")) {
      cardTitle = translate('你的设备'); // 仅在获取失败时显示"你的设备"
    }

    return PaddingCard(
        title: cardTitle, // 动态设置标题
        // 设置标题字体大小为18px
        titleTextStyle: const TextStyle(fontSize: 18.0, fontWeight: FontWeight.bold),
        // 移除标题图标
        titleIcon: null,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // SN号显示在标题下方，仅当有SN时显示，减少间距
            if (_deviceSN.isNotEmpty && _deviceSN != "Unknown")
              Padding(
                // 减少顶部和底部间距
                padding: const EdgeInsets.only(bottom: 10, top: 0),
                child: Text(
                  _deviceSN,
                  // 保持SN字体为25.0
                  style: const TextStyle(fontSize: 25.0, fontWeight: FontWeight.bold),
                ),
              ),
            
            // ID - 保留左侧图标
            Row(children: [
              const Icon(Icons.perm_identity,
                      color: Colors.grey, size: iconSize)
                  .marginOnly(right: iconMarginRight),
              Text(
                translate('ID'),
                style: textStyleHeading,
              )
            ]),
            Row(
              children: [
                Expanded(
                  child: Text(
                    model.serverId.value.text,
                    style: textStyleValue,
                  ),
                ),
              ],
            ).marginOnly(left: 39, bottom: 15),
            
            // 连接状态
            ConnectionStateNotification()
          ],
        ));
  }
}

// 恢复原有的androidChannelInit函数，添加SN处理功能
void androidChannelInit() {
  gFFI.setMethodCallHandler((method, arguments) {
    debugPrint("flutter got android msg: $method, $arguments");
    try {
      // 处理SN接收
      if (method == "on_sn_received" && arguments is Map) {
        final sn = arguments["sn"] as String?;
        debugPrint("收到设备SN: '$sn'");
        if (sn != null && sn.isNotEmpty && sn != "Unknown") {
          updateServerInfoSN(sn);
        }
        return "";
      }
      
      // 处理原有事件
      switch (method) {
        case "start_capture":
          {
            gFFI.dialogManager.dismissAll();
            gFFI.serverModel.updateClientState();
            break;
          }
        case "on_state_changed":
          {
            var name = arguments["name"] as String;
            var value = arguments["value"] as String == "true";
            debugPrint("from jvm:on_state_changed,$name:$value");
            gFFI.serverModel.changeStatue(name, value);
            break;
          }
        case "on_android_permission_result":
          {
            var type = arguments["type"] as String;
            var result = arguments["result"] as bool;
            AndroidPermissionManager.complete(type, result);
            break;
          }
        case "on_media_projection_canceled":
          {
            gFFI.serverModel.stopService();
            break;
          }
        case "msgbox":
          {
            var type = arguments["type"] as String;
            var title = arguments["title"] as String;
            var text = arguments["text"] as String;
            var link = (arguments["link"] ?? '') as String;
            msgBox(gFFI.sessionId, type, title, text, link, gFFI.dialogManager);
            break;
          }
        case "stop_service":
          {
            print(
                "stop_service by kotlin, isStart:${gFFI.serverModel.isStart}");
            if (gFFI.serverModel.isStart) {
              gFFI.serverModel.stopService();
            }
            break;
          }
      }
    } catch (e) {
      debugPrintStack(label: "MethodCallHandler err: $e");
    }
    return "";
  });
}

// 更新所有ServerInfo实例的SN
void updateServerInfoSN(String sn) {
  if (sn.isEmpty || sn == "Unknown") return;
  
  // 保存SN到本地存储
  try {
    bind.mainSetLocalOption(key: _ServerInfoState.snPrefKey, value: sn);
    debugPrint("SN自动保存到本地存储: $sn");
  } catch (e) {
    debugPrint("保存SN到本地存储失败: $e");
  }
  
  // 遍历所有Element查找ServerInfo组件
  void visitor(Element element) {
    if (element is StatefulElement && element.state is _ServerInfoState) {
      final state = element.state as _ServerInfoState;
      if (!state._hasFetchedSN || state._deviceSN.isEmpty) {
        state.setState(() {
          state._deviceSN = sn;
          state._hasFetchedSN = true;
          debugPrint("更新UI中的SN为: '$sn'");
        });
      }
    }
    element.visitChildren(visitor);
  }
  
  // 在下一帧执行，确保组件已经构建
  WidgetsBinding.instance.addPostFrameCallback((_) {
    final context = globalKey.currentContext;
    if (context != null) {
      (context as Element).visitChildren(visitor);
    }
  });
}

class PermissionChecker extends StatefulWidget {
  const PermissionChecker({Key? key}) : super(key: key);

  @override
  State<PermissionChecker> createState() => _PermissionCheckerState();
}

class _PermissionCheckerState extends State<PermissionChecker> {
  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);
    final hasAudioPermission = androidVersion >= 30;
    return PaddingCard(
        title: translate("权限"),
        // 只移除标题图标
        titleIcon: null,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          serverModel.mediaOk
              ? ElevatedButton.icon(
                      style: ButtonStyle(
                          backgroundColor:
                              MaterialStateProperty.all(Colors.red)),
                      icon: const Icon(Icons.stop),
                      onPressed: serverModel.toggleService,
                      label: Text(translate("Stop service")))
                  .marginOnly(bottom: 8)
              : SizedBox.shrink(),
          // 屏幕录制开关也不显示，因为有服务未运行页面的启动按钮和自动启动功能
          // SwitchListTile(
          //   visualDensity: VisualDensity.compact,
          //   contentPadding: EdgeInsets.all(0),
          //   title: Text(translate("Screen Capture")),
          //   value: serverModel.mediaOk,
          //   onChanged: serverModel.mediaOk ? null : (_) => serverModel.toggleService(),
          // ),
          // Input Control functionality is still active but not displayed in UI
          PermissionRow(translate("Transfer file"), serverModel.fileOk,
              serverModel.toggleFile),
          hasAudioPermission
              ? PermissionRow(translate("Audio Capture"), serverModel.audioOk,
                  serverModel.toggleAudio)
              : Row(children: [
                  // 保留内部图标
                  Icon(Icons.info_outline).marginOnly(right: 15),
                  Expanded(
                      child: Text(
                    translate("android_version_audio_tip"),
                    style: const TextStyle(color: MyTheme.darkGray),
                  ))
                ]),
          PermissionRow(translate("Enable clipboard"), serverModel.clipboardOk,
              serverModel.toggleClipboard),
        ]));
  }
}

class PermissionRow extends StatelessWidget {
  const PermissionRow(this.name, this.isOk, this.onPressed, {Key? key})
      : super(key: key);

  final String name;
  final bool isOk;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    // 如果是"输入控制"或"屏幕录制"且已启用，则禁用开关操作
    if ((name == translate("Input Control") || 
         name == translate("使用系统权限捕获屏幕") || 
         name == translate("Screen Capture")) && isOk) {
      return SwitchListTile(
        visualDensity: VisualDensity.compact,
        contentPadding: EdgeInsets.all(0),
        title: Text(name),
        value: isOk,
        onChanged: null, // 设置为null使开关变为只读
      );
     }
    
    return SwitchListTile(
        visualDensity: VisualDensity.compact,
        contentPadding: EdgeInsets.all(0),
        title: Text(name),
        value: isOk,
        onChanged: (bool value) {
          onPressed();
        });
  }
}

class ConnectionManager extends StatelessWidget {
  const ConnectionManager({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);
    if (serverModel.clients.isEmpty) return Offstage();
    return PaddingCard(
      title: translate("Connections"),
      // 保留默认图标
      // titleIcon: null,
      child: Column(
        children: [
          ...serverModel.clients.map(
            (client) => Column(
              children: [
                ClientInfo(client),
                (client.authStatus == AuthStatus.Other ||
                        client.authStatus == AuthStatus.Waiting)
                    ? Row(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          TextButton(
                              onPressed: () =>
                                  serverModel.sendLoginResponse(client, false),
                              child: Text(translate("Dismiss"))),
                          TextButton(
                              onPressed: () =>
                                  serverModel.sendLoginResponse(client, true),
                              child: Row(
                                children: [
                                  Text(translate("Accept")),
                                  const SizedBox(width: 4),
                                  const Icon(Icons.done, size: 18),
                                ],
                              ))
                        ],
                      )
                    : Row(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          if (client.inVoiceCall)
                            TextButton(
                              onPressed: () {
                                serverModel.closeVoiceCall();
                              },
                              child: Row(
                                children: [
                                  Text(translate("Close Voice Call")),
                                  const SizedBox(width: 4),
                                  const Icon(Icons.close, size: 18),
                                ],
                              ),
                            ),
                          const SizedBox(width: 4),
                          TextButton(
                            onPressed: () {
                              serverModel.sendDisconnect(client.id);
                            },
                            child: Row(
                              children: [
                                Text(translate("Disconnect")),
                                const SizedBox(width: 4),
                                const Icon(Icons.link_off, size: 18),
                              ],
                            ),
                          ),
                        ],
                      ),
                const Divider(),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class PaddingCard extends StatelessWidget {
  const PaddingCard({
    Key? key,
    required this.child,
    this.title,
    this.titleIcon,
    this.titleTextStyle,
  }) : super(key: key);

  final String? title;
  final Icon? titleIcon;
  final TextStyle? titleTextStyle;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    var cardChild = child;
    // 只有当标题不为空时才显示标题部分
    if (title != null && title!.isNotEmpty) {
      cardChild = Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              // 恢复默认图标显示逻辑，让特定组件自己控制是否显示图标
              if (titleIcon != null)
                titleIcon!.marginOnly(right: 10)
              else
                const Icon(Icons.info, color: MyTheme.accent)
                    .marginOnly(right: 10),
              Expanded(
                child: Text(
                  title!,
                  style: titleTextStyle ??
                      Theme.of(context)
                          .textTheme
                          .titleLarge
                          ?.copyWith(fontSize: 18, fontWeight: FontWeight.normal),
                ),
              ),
            ],
          ).marginOnly(bottom: 5),
          child,
        ],
      );
    }

    return Container(
      decoration: BoxDecoration(
        color: Theme.of(context).cardColor,
        borderRadius: const BorderRadius.all(Radius.circular(10.0)),
      ),
      margin: const EdgeInsets.fromLTRB(12.0, 10.0, 12.0, 10.0),
      padding: const EdgeInsets.all(12.0),
      child: cardChild,
    );
  }
}

class ClientInfo extends StatelessWidget {
  final Client client;
  ClientInfo(this.client);

  @override
  Widget build(BuildContext context) {
    return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(children: [
          Row(
            children: [
              Expanded(
                  flex: -1,
                  child: Padding(
                      padding: const EdgeInsets.only(right: 12),
                      child: CircleAvatar(
                          backgroundColor: str2color(
                              client.name,
                              Theme.of(context).brightness == Brightness.light
                                  ? 255
                                  : 150),
                          child: Text(client.name[0])))),
              Expanded(
                  child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                    Text(client.name, style: const TextStyle(fontSize: 18)),
                    const SizedBox(width: 8),
                    Text(client.peerId, style: const TextStyle(fontSize: 10))
                  ]))
            ],
          ),
        ]));
  }
}

void showScamWarning(BuildContext context, ServerModel serverModel) {
  showDialog(
    context: context,
    builder: (BuildContext context) {
      return ScamWarningDialog(serverModel: serverModel);
    },
  );
}
