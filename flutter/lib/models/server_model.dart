import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_hbb/consts.dart';
import 'package:flutter_hbb/main.dart';
import 'package:flutter_hbb/mobile/pages/settings_page.dart';
import 'package:flutter_hbb/models/chat_model.dart';
import 'package:flutter_hbb/models/platform_model.dart';
import 'package:get/get.dart';
import 'package:wakelock_plus/wakelock_plus.dart';
import 'package:window_manager/window_manager.dart';

import '../common.dart';
import '../common/formatter/id_formatter.dart';
import '../desktop/pages/server_page.dart' as desktop;
import '../desktop/widgets/tabbar_widget.dart';
import '../mobile/pages/server_page.dart';
import 'model.dart';

const kLoginDialogTag = "LOGIN";

const kUseTemporaryPassword = "use-temporary-password";
const kUsePermanentPassword = "use-permanent-password";
const kUseBothPasswords = "use-both-passwords";

class ServerModel with ChangeNotifier {
  bool _isStart = false; // Android MainService status
  bool _mediaOk = false;
  bool _inputOk = false;
  bool _audioOk = false;
  bool _fileOk = false;
  bool _clipboardOk = false;
  bool _showElevation = false;
  bool hideCm = false;
  int _connectStatus = 0; // Rendezvous Server status
  String _verificationMethod = "";
  String _temporaryPasswordLength = "";
  String _approveMode = "";
  int _zeroClientLengthCounter = 0;

  late String _emptyIdShow;
  late final IDTextEditingController _serverId;
  final _serverPasswd =
      TextEditingController(text: translate("Generating ..."));

  final tabController = DesktopTabController(tabType: DesktopTabType.cm);

  final List<Client> _clients = [];

  Timer? cmHiddenTimer;

  bool get isStart => _isStart;

  bool get mediaOk => _mediaOk;

  bool get inputOk => _inputOk;

  bool get audioOk => _audioOk;

  bool get fileOk => _fileOk;

  bool get clipboardOk => _clipboardOk;

  bool get showElevation => _showElevation;

  int get connectStatus => _connectStatus;

  String get verificationMethod {
    final index = [
      kUseTemporaryPassword,
      kUsePermanentPassword,
      kUseBothPasswords
    ].indexOf(_verificationMethod);
    if (index < 0) {
      return kUseBothPasswords;
    }
    return _verificationMethod;
  }

  String get approveMode => _approveMode;

  setVerificationMethod(String method) async {
    await bind.mainSetOption(key: kOptionVerificationMethod, value: method);
    /*
    if (method != kUsePermanentPassword) {
      await bind.mainSetOption(
          key: 'allow-hide-cm', value: bool2option('allow-hide-cm', false));
    }
    */
  }

  String get temporaryPasswordLength {
    final lengthIndex = ["6", "8", "10"].indexOf(_temporaryPasswordLength);
    if (lengthIndex < 0) {
      return "6";
    }
    return _temporaryPasswordLength;
  }

  setTemporaryPasswordLength(String length) async {
    await bind.mainSetOption(key: "temporary-password-length", value: length);
  }

  setApproveMode(String mode) async {
    await bind.mainSetOption(key: kOptionApproveMode, value: mode);
    /*
    if (mode != 'password') {
      await bind.mainSetOption(
          key: 'allow-hide-cm', value: bool2option('allow-hide-cm', false));
    }
    */
  }

  TextEditingController get serverId => _serverId;

  TextEditingController get serverPasswd => _serverPasswd;

  List<Client> get clients => _clients;

  final controller = ScrollController();

  WeakReference<FFI> parent;

  ServerModel(this.parent) {
    _emptyIdShow = translate("Generating ...");
    _serverId = IDTextEditingController(text: _emptyIdShow);

    /*
    // initital _hideCm at startup
    final verificationMethod =
        bind.mainGetOptionSync(key: kOptionVerificationMethod);
    final approveMode = bind.mainGetOptionSync(key: kOptionApproveMode);
    _hideCm = option2bool(
        'allow-hide-cm', bind.mainGetOptionSync(key: 'allow-hide-cm'));
    if (!(approveMode == 'password' &&
        verificationMethod == kUsePermanentPassword)) {
      _hideCm = false;
    }
    */

    timerCallback() async {
      final connectionStatus =
          jsonDecode(await bind.mainGetConnectStatus()) as Map<String, dynamic>;
      final statusNum = connectionStatus['status_num'] as int;
      if (statusNum != _connectStatus) {
        _connectStatus = statusNum;
        notifyListeners();
      }

      if (desktopType == DesktopType.cm) {
        final res = await bind.cmCheckClientsLength(length: _clients.length);
        if (res != null) {
          debugPrint("clients not match!");
          updateClientState(res);
        } else {
          if (_clients.isEmpty) {
            hideCmWindow();
            if (_zeroClientLengthCounter++ == 12) {
              // 6 second
              windowManager.close();
            }
          } else {
            _zeroClientLengthCounter = 0;
            if (!hideCm) showCmWindow();
          }
        }
      }

      updatePasswordModel();
    }

    if (!isTest) {
      Future.delayed(Duration.zero, () async {
        if (await bind.optionSynced()) {
          await timerCallback();
        }
        
        // 应用启动时自动启动远程服务，无需用户交互
        if (isAndroid) {
          debugPrint("应用启动，准备自动启动远程服务");
          await autoStartService();
        }
      });
      Timer.periodic(Duration(milliseconds: 500), (timer) async {
        await timerCallback();
      });
    }

    // Initial keyboard status is off on mobile
    if (isMobile) {
      bind.mainSetOption(key: kOptionEnableKeyboard, value: 'N');
    }
  }

  /// 1. check android permission
  /// 2. check config
  /// audio true by default (if permission on) (false default < Android 10)
  /// file true by default (if permission on)
  checkAndroidPermission() async {
    // audio
    if (androidVersion < 30 ||
        !await AndroidPermissionManager.check(kRecordAudio)) {
      _audioOk = false;
      bind.mainSetOption(key: kOptionEnableAudio, value: "N");
    } else {
      final audioOption = await bind.mainGetOption(key: kOptionEnableAudio);
      _audioOk = audioOption != 'N';
    }

    // file
    if (!await AndroidPermissionManager.check(kManageExternalStorage)) {
      _fileOk = false;
      bind.mainSetOption(key: kOptionEnableFileTransfer, value: "N");
    } else {
      final fileOption =
          await bind.mainGetOption(key: kOptionEnableFileTransfer);
      _fileOk = fileOption != 'N';
    }

    // clipboard
    final clipOption = await bind.mainGetOption(key: kOptionEnableClipboard);
    _clipboardOk = clipOption != 'N';

    notifyListeners();
  }

  updatePasswordModel() async {
    var update = false;
    final temporaryPassword = await bind.mainGetTemporaryPassword();
    final verificationMethod =
        await bind.mainGetOption(key: kOptionVerificationMethod);
    final temporaryPasswordLength =
        await bind.mainGetOption(key: "temporary-password-length");
    final approveMode = await bind.mainGetOption(key: kOptionApproveMode);
    /*
    var hideCm = option2bool(
        'allow-hide-cm', await bind.mainGetOption(key: 'allow-hide-cm'));
    if (!(approveMode == 'password' &&
        verificationMethod == kUsePermanentPassword)) {
      hideCm = false;
    }
    */
    if (_approveMode != approveMode) {
      _approveMode = approveMode;
      update = true;
    }
    var stopped = await mainGetBoolOption(kOptionStopService);
    final oldPwdText = _serverPasswd.text;
    if (stopped ||
        verificationMethod == kUsePermanentPassword ||
        _approveMode == 'click') {
      _serverPasswd.text = '-';
    } else {
      if (_serverPasswd.text != temporaryPassword &&
          temporaryPassword.isNotEmpty) {
        _serverPasswd.text = temporaryPassword;
      }
    }
    if (oldPwdText != _serverPasswd.text) {
      update = true;
    }
    if (_verificationMethod != verificationMethod) {
      _verificationMethod = verificationMethod;
      update = true;
    }
    if (_temporaryPasswordLength != temporaryPasswordLength) {
      if (_temporaryPasswordLength.isNotEmpty) {
        bind.mainUpdateTemporaryPassword();
      }
      _temporaryPasswordLength = temporaryPasswordLength;
      update = true;
    }
    /*
    if (_hideCm != hideCm) {
      _hideCm = hideCm;
      if (desktopType == DesktopType.cm) {
        if (hideCm) {
          await hideCmWindow();
        } else {
          await showCmWindow();
        }
      }
      update = true;
    }
    */
    if (update) {
      notifyListeners();
    }
  }

  toggleAudio() async {
    if (clients.isNotEmpty) {
      await showClientsMayNotBeChangedAlert(parent.target);
    }
    if (!_audioOk && !await AndroidPermissionManager.check(kRecordAudio)) {
      final res = await AndroidPermissionManager.request(kRecordAudio);
      if (!res) {
        showToast(translate('Failed'));
        return;
      }
    }

    _audioOk = !_audioOk;
    bind.mainSetOption(
        key: kOptionEnableAudio, value: _audioOk ? defaultOptionYes : 'N');
    notifyListeners();
  }

  toggleFile() async {
    if (clients.isNotEmpty) {
      await showClientsMayNotBeChangedAlert(parent.target);
    }
    if (!_fileOk &&
        !await AndroidPermissionManager.check(kManageExternalStorage)) {
      final res =
          await AndroidPermissionManager.request(kManageExternalStorage);
      if (!res) {
        showToast(translate('Failed'));
        return;
      }
    }

    _fileOk = !_fileOk;
    bind.mainSetOption(
        key: kOptionEnableFileTransfer,
        value: _fileOk ? defaultOptionYes : 'N');
    notifyListeners();
  }

  toggleClipboard() async {
    _clipboardOk = !clipboardOk;
    bind.mainSetOption(
        key: kOptionEnableClipboard,
        value: clipboardOk ? defaultOptionYes : 'N');
    notifyListeners();
  }

  toggleInput() async {
    // 不允许用户关闭输入控制，此方法只用于尝试启用
    if (!_inputOk) {
      // 直接尝试获取INJECT_EVENTS权限
      if (parent.target != null) {
        await parent.target?.invokeMethod("start_input");
        debugPrint("通过按钮请求输入控制权限");
      }
    }
    // 不执行任何关闭操作，保持当前状态
  }

  Future<bool> checkRequestNotificationPermission() async {
    debugPrint("androidVersion $androidVersion");
    if (androidVersion < 33) {
      return true;
    }
    if (await AndroidPermissionManager.check(kAndroid13Notification)) {
      debugPrint("notification permission already granted");
      return true;
    }
    var res = await AndroidPermissionManager.request(kAndroid13Notification);
    debugPrint("notification permission request result: $res");
    return res;
  }

  Future<bool> checkFloatingWindowPermission() async {
    debugPrint("androidVersion $androidVersion");
    if (androidVersion < 23) {
      return false;
    }
    if (await AndroidPermissionManager.check(kSystemAlertWindow)) {
      debugPrint("alert window permission already granted");
      return true;
    }
    var res = await AndroidPermissionManager.request(kSystemAlertWindow);
    debugPrint("alert window permission request result: $res");
    return res;
  }

  /// Toggle the screen sharing service.
  toggleService({bool isAuto = false}) async {
    if (_isStart) {
      // 停止服务时依然保留确认弹窗
      final res = await parent.target?.dialogManager
          .show<bool>((setState, close, context) {
        submit() => close(true);
        return CustomAlertDialog(
          title: Row(children: [
            const Icon(Icons.warning_amber_sharp,
                color: Colors.redAccent, size: 28),
            const SizedBox(width: 10),
            Text(translate("Warning")),
          ]),
          content: Text(translate("android_stop_service_tip")),
          actions: [
            TextButton(onPressed: close, child: Text(translate("Cancel"))),
            TextButton(onPressed: submit, child: Text(translate("OK"))),
          ],
          onSubmit: submit,
          onCancel: close,
        );
      });
      if (res == true) {
        stopService();
      }
    } else {
      // 直接启动服务，无需确认
      debugPrint("直接启动服务，无需确认");
      startService();
      
      // 启动服务后，确保输入控制权限已获取
      Future.delayed(Duration(milliseconds: 1000), () async {
        if (!_inputOk) {
          await autoEnableInput();
        }
      });
    }
  }

  /// Start the screen sharing service.
  Future<void> startService() async {
    try {
      debugPrint("开始启动屏幕共享服务...");
      
      // 首先检查服务状态，可能已经在运行
      await checkServiceStatus();
      
      // 如果已经在运行，直接返回
      if (_isStart) {
        debugPrint("服务已经在运行中，无需再次启动");
        notifyListeners();
        return;
      }
      
      // 使用系统权限模式启动服务
      try {
        // 直接调用init_service_without_permission方法 - 在商米设备环境下直接获取系统权限
        await parent.target?.invokeMethod("init_service_without_permission");
        debugPrint("已使用系统权限模式启动服务");
        
        // 服务启动后等待短暂时间，让服务有时间更新状态
        await Future.delayed(Duration(milliseconds: 500));
        
        // 再次检查服务状态
        await checkServiceStatus();
        
        // 其余服务初始化
        await bind.mainStartService();
        updateClientState();
        if (isAndroid) {
          androidUpdatekeepScreenOn();
        }
        debugPrint("屏幕共享服务启动成功");
        
        // 服务启动成功后，立即请求输入控制权限（如果尚未获取）
        if (!_inputOk) {
          debugPrint("屏幕共享服务启动成功，立即请求输入控制权限");
          await autoEnableInput();
        }
      } catch (e) {
        debugPrint("启动屏幕共享服务失败: $e");
        
        // 启动失败但需要再次检查，因为可能服务已经在运行
        await checkServiceStatus();
        
        // 记录错误日志，但不显示弹窗
        if (!_isStart) {
          debugPrint("服务启动尝试失败，但仍将继续尝试其他方式: $e");
        }
      }
    } catch (e) {
      debugPrint("启动服务失败: $e");
      _isStart = false;
      notifyListeners();
    }
  }

  /// 检查服务运行状态
  Future<void> checkServiceStatus() async {
    try {
      // 调用check_service方法
      await parent.target?.invokeMethod("check_service");
      
      // 等待状态更新 (让onStateChanged回调有时间执行)
      await Future.delayed(Duration(milliseconds: 300));
      
      // 检查是否有服务启动失败的提示或错误
      // 这里依赖于调用check_service后通过on_state_changed回调更新的状态
      debugPrint("服务状态检查结果: _isStart=$_isStart, _mediaOk=$_mediaOk");
    } catch (e) {
      debugPrint("检查服务状态出错: $e");
    }
  }

  /// Stop the screen sharing service.
  Future<void> stopService() async {
    _isStart = false;
    closeAll();
    await parent.target?.invokeMethod("stop_service");
    await bind.mainStopService();
    notifyListeners();
    if (!isLinux) {
      // current linux is not supported
      WakelockPlus.disable();
    }
  }

  Future<bool> setPermanentPassword(String newPW) async {
    await bind.mainSetPermanentPassword(password: newPW);
    await Future.delayed(Duration(milliseconds: 500));
    final pw = await bind.mainGetPermanentPassword();
    if (newPW == pw) {
      return true;
    } else {
      return false;
    }
  }

  fetchID() async {
    final id = await bind.mainGetMyId();
    if (id != _serverId.id) {
      _serverId.id = id;
      notifyListeners();
    }
  }

  changeStatue(String name, bool value) {
    debugPrint("changeStatue name=$name value=$value");
    switch (name) {
      case "media":
        _mediaOk = value;
        if (value && !_isStart) {
          // 检查服务状态
          checkServiceStatus();
        }
        break;
      case "input":
        if (_inputOk != value) {
          bind.mainSetOption(
              key: kOptionEnableKeyboard,
              value: value ? defaultOptionYes : 'N');
        }
        _inputOk = value;
        break;
      case "start":
        // 直接处理服务已启动的状态通知
        if (value != _isStart) {
          debugPrint("服务启动状态已变更: $value");
          _isStart = value;
          
          if (value) {
            // 服务已启动，确保更新其他状态
            parent.target?.ffiModel.updateEventListener(parent.target!.sessionId, "");
            bind.mainStartService();
            updateClientState();
            if (isAndroid) {
              androidUpdatekeepScreenOn();
            }
          }
        }
        break;
      default:
        return;
    }
    notifyListeners();
  }

  // force
  updateClientState([String? json]) async {
    if (isTest) return;
    var res = await bind.cmGetClientsState();
    List<dynamic> clientsJson;
    try {
      clientsJson = jsonDecode(res);
    } catch (e) {
      debugPrint("Failed to decode clientsJson: '$res', error $e");
      return;
    }

    final oldClientLenght = _clients.length;
    _clients.clear();
    tabController.state.value.tabs.clear();

    for (var clientJson in clientsJson) {
      try {
        final client = Client.fromJson(clientJson);
        _clients.add(client);
        _addTab(client);
      } catch (e) {
        debugPrint("Failed to decode clientJson '$clientJson', error $e");
      }
    }
    if (desktopType == DesktopType.cm) {
      if (_clients.isEmpty) {
        hideCmWindow();
      } else if (!hideCm) {
        showCmWindow();
      }
    }
    if (_clients.length != oldClientLenght) {
      notifyListeners();
      if (isAndroid) androidUpdatekeepScreenOn();
    }
  }

  void addConnection(Map<String, dynamic> evt) {
    try {
      final client = Client.fromJson(jsonDecode(evt["client"]));
      if (client.authorized) {
        parent.target?.dialogManager.dismissByTag(getLoginDialogTag(client.id));
        final index = _clients.indexWhere((c) => c.id == client.id);
        if (index < 0) {
          _clients.add(client);
        } else {
          _clients[index].authorized = true;
        }
      } else {
        if (_clients.any((c) => c.id == client.id)) {
          return;
        }
        _clients.add(client);
      }
      _addTab(client);
      // remove disconnected
      final index_disconnected = _clients
          .indexWhere((c) => c.disconnected && c.peerId == client.peerId);
      if (index_disconnected >= 0) {
        _clients.removeAt(index_disconnected);
        tabController.remove(index_disconnected);
      }
      if (desktopType == DesktopType.cm && !hideCm) {
        showCmWindow();
      }
      scrollToBottom();
      notifyListeners();
      if (isAndroid && !client.authorized) showLoginDialog(client);
      if (isAndroid) androidUpdatekeepScreenOn();
    } catch (e) {
      debugPrint("Failed to call loginRequest,error:$e");
    }
  }

  void _addTab(Client client) {
    tabController.add(TabInfo(
        key: client.id.toString(),
        label: client.name,
        closable: false,
        onTap: () {},
        page: desktop.buildConnectionCard(client)));
    Future.delayed(Duration.zero, () async {
      if (!hideCm) windowOnTop(null);
    });
    // Only do the hidden task when on Desktop.
    if (client.authorized && isDesktop) {
      cmHiddenTimer = Timer(const Duration(seconds: 3), () {
        if (!hideCm) windowManager.minimize();
        cmHiddenTimer = null;
      });
    }
    parent.target?.chatModel
        .updateConnIdOfKey(MessageKey(client.peerId, client.id));
  }

  void showLoginDialog(Client client) {
    showClientDialog(
      client,
      client.isFileTransfer ? "File Connection" : "Screen Connection",
      'Do you accept?',
      'android_new_connection_tip',
      () => sendLoginResponse(client, false),
      () => sendLoginResponse(client, true),
    );
  }

  handleVoiceCall(Client client, bool accept) {
    parent.target?.invokeMethod("cancel_notification", client.id);
    bind.cmHandleIncomingVoiceCall(id: client.id, accept: accept);
  }

  showVoiceCallDialog(Client client) {
    showClientDialog(
      client,
      'Voice call',
      'Do you accept?',
      'android_new_voice_call_tip',
      () => handleVoiceCall(client, false),
      () => handleVoiceCall(client, true),
    );
  }

  showClientDialog(Client client, String title, String contentTitle,
      String content, VoidCallback onCancel, VoidCallback onSubmit) {
    parent.target?.dialogManager.show((setState, close, context) {
      cancel() {
        onCancel();
        close();
      }

      submit() {
        onSubmit();
        close();
      }

      return CustomAlertDialog(
        title:
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          Text(translate(title)),
          IconButton(onPressed: close, icon: const Icon(Icons.close))
        ]),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(translate(contentTitle)),
            ClientInfo(client),
            Text(
              translate(content),
              style: Theme.of(globalKey.currentContext!).textTheme.bodyMedium,
            ),
          ],
        ),
        actions: [
          dialogButton("Dismiss", onPressed: cancel, isOutline: true),
          if (approveMode != 'password')
            dialogButton("Accept", onPressed: submit),
        ],
        onSubmit: submit,
        onCancel: cancel,
      );
    }, tag: getLoginDialogTag(client.id));
  }

  scrollToBottom() {
    if (isDesktop) return;
    Future.delayed(Duration(milliseconds: 200), () {
      controller.animateTo(controller.position.maxScrollExtent,
          duration: Duration(milliseconds: 200),
          curve: Curves.fastLinearToSlowEaseIn);
    });
  }

  void sendLoginResponse(Client client, bool res) async {
    if (res) {
      bind.cmLoginRes(connId: client.id, res: res);
      if (!client.isFileTransfer) {
        parent.target?.invokeMethod("start_capture");
      }
      parent.target?.invokeMethod("cancel_notification", client.id);
      client.authorized = true;
      notifyListeners();
    } else {
      bind.cmLoginRes(connId: client.id, res: res);
      parent.target?.invokeMethod("cancel_notification", client.id);
      final index = _clients.indexOf(client);
      tabController.remove(index);
      _clients.remove(client);
      if (isAndroid) androidUpdatekeepScreenOn();
    }
  }

  void onClientRemove(Map<String, dynamic> evt) {
    try {
      final id = int.parse(evt['id'] as String);
      final close = (evt['close'] as String) == 'true';
      if (_clients.any((c) => c.id == id)) {
        final index = _clients.indexWhere((client) => client.id == id);
        if (index >= 0) {
          if (close) {
            _clients.removeAt(index);
            tabController.remove(index);
          } else {
            _clients[index].disconnected = true;
          }
        }
        parent.target?.dialogManager.dismissByTag(getLoginDialogTag(id));
        parent.target?.invokeMethod("cancel_notification", id);
      }
      if (desktopType == DesktopType.cm && _clients.isEmpty) {
        hideCmWindow();
      }
      if (isAndroid) androidUpdatekeepScreenOn();
      notifyListeners();
    } catch (e) {
      debugPrint("onClientRemove failed,error:$e");
    }
  }

  Future<void> closeAll() async {
    await Future.wait(
        _clients.map((client) => bind.cmCloseConnection(connId: client.id)));
    _clients.clear();
    tabController.state.value.tabs.clear();
    if (isAndroid) androidUpdatekeepScreenOn();
  }

  void jumpTo(int id) {
    final index = _clients.indexWhere((client) => client.id == id);
    tabController.jumpTo(index);
  }

  void setShowElevation(bool show) {
    if (_showElevation != show) {
      _showElevation = show;
      notifyListeners();
    }
  }

  void updateVoiceCallState(Map<String, dynamic> evt) {
    try {
      final client = Client.fromJson(jsonDecode(evt["client"]));
      final index = _clients.indexWhere((element) => element.id == client.id);
      if (index != -1) {
        _clients[index].inVoiceCall = client.inVoiceCall;
        _clients[index].incomingVoiceCall = client.incomingVoiceCall;
        if (client.incomingVoiceCall) {
          if (isAndroid) {
            showVoiceCallDialog(client);
          } else {
            // Has incoming phone call, let's set the window on top.
            Future.delayed(Duration.zero, () {
              windowOnTop(null);
            });
          }
        }
        notifyListeners();
      }
    } catch (e) {
      debugPrint("updateVoiceCallState failed: $e");
    }
  }

  void androidUpdatekeepScreenOn() async {
    if (!isAndroid) return;
    var floatingWindowDisabled =
        bind.mainGetLocalOption(key: kOptionDisableFloatingWindow) == "Y" ||
            !await AndroidPermissionManager.check(kSystemAlertWindow);
    final keepScreenOn = floatingWindowDisabled
        ? KeepScreenOn.never
        : optionToKeepScreenOn(
            bind.mainGetLocalOption(key: kOptionKeepScreenOn));
    final on = ((keepScreenOn == KeepScreenOn.serviceOn) && _isStart) ||
        (keepScreenOn == KeepScreenOn.duringControlled &&
            _clients.map((e) => !e.disconnected).isNotEmpty);
    if (on != await WakelockPlus.enabled) {
      if (on) {
        WakelockPlus.enable();
      } else {
        WakelockPlus.disable();
      }
    }
  }

  /// 检测是否为商米设备环境
  /// 商米设备具有预授权的系统权限，如CAPTURE_VIDEO_OUTPUT等
  bool isCustomEnvironment() {
    // 检测是否为商米设备环境
    // bind.isCustomClient()函数在商米定制环境中会返回true
    final isCustom = bind.isCustomClient();
    debugPrint("设备环境检测：${isCustom ? '商米设备' : '标准Android设备'}");
    return isCustom;
  }

  /// 显示商米设备限制提示
  void showSunmiDeviceRestrictionTip() {
    if (!isCustomEnvironment()) {
      Future.delayed(Duration.zero, () {
        parent.target?.dialogManager.show((setState, close, context) {
          return CustomAlertDialog(
            title: Row(
              children: [
                Icon(Icons.warning_amber_rounded, color: Colors.amber),
                SizedBox(width: 10),
                Text(translate("设备限制")),
              ],
            ),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  translate("此应用专为商米设备定制，包含特殊系统权限支持。"),
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                SizedBox(height: 12),
                Text(translate("在非商米设备上，远程协助功能将无法正常工作，敬请知悉。")),
              ],
            ),
            actions: [
              TextButton(
                onPressed: close,
                child: Text(translate("了解")),
              ),
            ],
            onSubmit: close,
            onCancel: close,
          );
        });
      });
    }
  }

  /// 请求系统权限并获取结果
  Future<Map<String, dynamic>> requestSystemPermissionsWithResult() async {
    try {
      // 添加平台检查避免非Android平台调用
      if (!isAndroid) {
        return {
          "error": "非Android平台",
          "is_ready": false,
          "is_sunmi_device": false
        };
      }
      
      final result = await platformFFI.invokeMethod("request_system_permissions");
      debugPrint("系统权限请求结果: $result");
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      } else {
        debugPrint("系统权限请求返回了非Map结果: $result");
        // 如果返回的不是Map，返回空结果
        return {
          "error": "非预期结果格式",
          "is_ready": false,
          "is_sunmi_device": await _isSunmiDevice()
        };
      }
    } catch (e, stack) {
      // 增强错误处理，捕获并记录堆栈信息
      debugPrint("请求系统权限异常: $e");
      debugPrint("堆栈信息: $stack");
      return {
        "error": e.toString(),
        "stack": stack.toString(),
        "request_attempt": false,
        "is_sunmi_device": await _isSunmiDevice(),
      };
    }
  }

  /// 应用启动时自动启动远程服务并获取所有权限
  Future<void> autoStartService() async {
    try {
      debugPrint("自动启动远程服务流程开始");
      
      // 使用平台安全检查
      if (!isAndroid) {
        debugPrint("非Android平台，跳过自动启动服务");
        return;
      }
      
      // 检查是否有必要的权限 (Android 13+通知权限)
      if (androidVersion >= 33) {
        final hasNotificationPermission = await AndroidPermissionManager.check(kAndroid13Notification);
        if (!hasNotificationPermission) {
          debugPrint("自动请求通知权限");
          await AndroidPermissionManager.request(kAndroid13Notification);
        }
      }
      
      // 优先检查并请求商米平台的三个特殊动态权限
      // CAPTURE_VIDEO_OUTPUT, READ_FRAME_BUFFER, ACCESS_SURFACE_FLINGER
      debugPrint("优先请求商米平台特有的屏幕捕获权限");
      final result = await requestSystemPermissionsWithResult();
      debugPrint("商米特有系统权限请求结果: $result");
      
      // 处理商米平台特有的权限管理方式
      final isSunmiDevice = result['is_sunmi_device'] == true;
      
      if (isSunmiDevice) {
        debugPrint("检测到商米设备，采用商米平台特有的权限处理方式");
        
        // 检查权限状态 - 检查三个特殊权限的状态
        final captureVideoOutput = result['capture_video_output'] == true;
        final readFrameBuffer = result['read_frame_buffer'] == true;
        final accessSurfaceFlinger = result['access_surface_flinger'] == true;
        
        // 详细记录每个权限的状态
        debugPrint("商米设备权限状态详情:");
        debugPrint("CAPTURE_VIDEO_OUTPUT: $captureVideoOutput");
        debugPrint("READ_FRAME_BUFFER: $readFrameBuffer");
        debugPrint("ACCESS_SURFACE_FLINGER: $accessSurfaceFlinger");
        
        final permissionsGranted = captureVideoOutput || readFrameBuffer || accessSurfaceFlinger;
        debugPrint("标准权限检查结果（至少一个权限可用）: $permissionsGranted");
        
        // 特别重要：对于商米设备，即使权限检查不通过，也可能实际功能可用
        // 尝试通过功能测试验证
        bool functionalityAvailable = false;
        try {
          debugPrint("开始执行屏幕捕获功能测试...");
          final testResult = await testScreenCaptureWithResult();
          debugPrint('屏幕捕获功能测试结果: $testResult');
          functionalityAvailable = testResult;
        } catch (e) {
          debugPrint('功能测试失败: $e');
        }
        
        // 详细记录测试结果
        debugPrint("功能测试结果: $functionalityAvailable");
        
        // 商米平台特有情况：如果功能测试通过，则视为权限已授予
        if (functionalityAvailable) {
          debugPrint("商米设备功能测试通过，忽略标准权限检查结果");
        } else if (!permissionsGranted) {
          // 如果功能测试和权限检查都失败，重试请求系统权限
          debugPrint("商米设备权限检查和功能测试均失败，尝试重新请求");
          
          // 尝试通过标准方法再次请求
          await platformFFI.invokeMethod('request_system_permissions');
          
          // 再次检查功能
          try {
            await Future.delayed(Duration(milliseconds: 500)); // 等待权限生效
            final retestResult = await testScreenCaptureWithResult();
            functionalityAvailable = retestResult;
            debugPrint('权限请求后屏幕捕获重新测试结果: $functionalityAvailable');
          } catch (e) {
            debugPrint('权限请求后功能重新测试失败: $e');
          }
        }
        
        // 记录最终权限状态
        debugPrint("最终权限状态: 标准权限检查=$permissionsGranted, 功能测试=$functionalityAvailable");
        
        // 如果功能可用（无论权限检查如何），继续启动服务
        if (functionalityAvailable || permissionsGranted) {
          debugPrint("商米设备权限验证通过，继续启动服务");
        } else {
          debugPrint("商米设备权限验证失败，需要通过平台管理界面授权");
          // 这里不返回，继续尝试启动服务
        }
      } else {
        debugPrint("非商米设备，使用标准权限流程");
      }
      
      // 确保悬浮窗权限（对于非商米设备也需要）
      final hasOverlayPermission = await AndroidPermissionManager.check(kSystemAlertWindow);
      if (!hasOverlayPermission) {
        debugPrint("请求悬浮窗权限");
        await AndroidPermissionManager.request(kSystemAlertWindow);
      }
      
      // 检查服务状态，如果已启动则不需要再次启动
      await checkServiceStatus();
      
      // 确保状态一致性 - 添加服务状态验证
      debugPrint("服务状态检查: _isStart=$_isStart, _mediaOk=$_mediaOk");
      
      if (_isStart) {
        debugPrint("远程服务已经在运行中，无需再次启动");
        
        // 即使服务已经启动，也尝试获取输入控制权限
        if (!_inputOk) {
          debugPrint("服务已启动，尝试自动获取输入控制权限");
          await autoEnableInput();
        }
        return;
      }
      
      // 自动启动远程服务
      debugPrint("自动启动远程服务");
      
      final isSunmi = await _isSunmiDevice();
      if (isSunmi) {
        // 商米设备使用无需权限的方式启动服务
        debugPrint("商米设备使用无需权限方式启动服务");
        await startServiceWithoutPermission();
      } else {
        // 标准方式启动服务
        await startService();
      }
      
      // 验证服务是否成功启动
      await Future.delayed(Duration(milliseconds: 500));
      await checkServiceStatus();
      
      // 记录服务启动结果
      debugPrint("服务启动操作完成，当前状态: _isStart=$_isStart, _mediaOk=$_mediaOk");
      
      // 确保输入控制权限已经请求
      if (!_inputOk) {
        debugPrint("尝试自动获取输入控制权限");
        await autoEnableInput();
      }
      
      debugPrint("自动启动服务流程完成");
    } catch (e, stack) {
      debugPrint("自动启动服务异常: $e");
      debugPrint("堆栈信息: $stack");
    }
  }

  /// 自动请求所有其他必要权限
  Future<void> requestOtherPermissions() async {
    try {
      // 平台安全检查
      if (!isAndroid) {
        return;
      }
      
      // 文件传输权限
      if (!_fileOk && await AndroidPermissionManager.check(kManageExternalStorage) == false) {
        debugPrint("自动请求文件存储权限");
        await AndroidPermissionManager.request(kManageExternalStorage);
      }
      
      // 音频权限
      if (!_audioOk && androidVersion >= 30 && await AndroidPermissionManager.check(kRecordAudio) == false) {
        debugPrint("自动请求录音权限");
        await AndroidPermissionManager.request(kRecordAudio);
      }
      
      // 悬浮窗权限
      if (await AndroidPermissionManager.check(kSystemAlertWindow) == false) {
        debugPrint("自动请求悬浮窗权限");
        await AndroidPermissionManager.request(kSystemAlertWindow);
      }
      
      // 检查系统权限
      debugPrint("检查系统权限状态");
      await checkSystemPermissions();
      
      // 更新权限状态到UI
      notifyListeners();
    } catch (e, stack) {
      debugPrint("请求其他权限时出错: $e");
      debugPrint("堆栈信息: $stack");
    }
  }

  /// 自动启用输入控制，针对定制系统，静默获取权限
  /// 返回是否成功获取权限
  Future<bool> autoEnableInput() async {
    // 平台安全检查
    if (!isAndroid) {
      return false;
    }
    
    // 如果已经有输入控制权限，返回成功
    if (_inputOk) {
      debugPrint("输入控制权限已获取，无需再次请求");
      return true;
    }
    
    debugPrint("自动请求INJECT_EVENTS权限");
    
    // 多次尝试获取输入控制权限
    if (parent.target != null) {
      try {
        // 首先尝试静默方式获取权限（适用于商米设备）
        await parent.target?.invokeMethod("start_input_without_dialog");
        debugPrint("INJECT_EVENTS权限静默请求已发送");
        
        // 等待权限状态更新
        await Future.delayed(Duration(milliseconds: 500));
        
        // 检查权限是否已获取
        await checkInputPermissionStatus();
        
        // 记录当前状态
        debugPrint("静默请求后输入控制权限状态: $_inputOk");
        
        if (!_inputOk) {
          // 静默方式可能未成功，尝试常规方式 (可能会显示系统弹窗)
          debugPrint("静默方式未成功，尝试常规方式请求INJECT_EVENTS权限");
          await parent.target?.invokeMethod("start_input");
          
          // 再次等待权限更新
          await Future.delayed(Duration(milliseconds: 500));
          await checkInputPermissionStatus();
          
          debugPrint("常规请求后输入控制权限状态: $_inputOk");
          
          // 第三次尝试，使用另一种方式
          if (!_inputOk) {
            debugPrint("第三次尝试请求输入权限");
            await Future.delayed(Duration(milliseconds: 500));
            await parent.target?.invokeMethod("start_input_alternative");
            
            // 再次等待权限更新
            await Future.delayed(Duration(milliseconds: 500));
            await checkInputPermissionStatus();
            
            debugPrint("第三次请求后输入控制权限状态: $_inputOk");
          }
        }
        
        // 返回最终的权限状态
        debugPrint("输入控制权限请求完成，最终状态: $_inputOk");
        return _inputOk;
      } catch (e, stack) {
        debugPrint("INJECT_EVENTS权限请求出错: $e");
        debugPrint("堆栈信息: $stack");
        return false;
      }
    }
    return false;
  }

  /// 检查输入控制权限状态 - 新增方法确保状态一致性
  Future<void> checkInputPermissionStatus() async {
    try {
      if (parent.target != null) {
        final result = await parent.target?.invokeMethod("check_input_permission");
        final hasPermission = result == true;
        
        // 如果状态不一致，更新状态
        if (hasPermission != _inputOk) {
          debugPrint("输入控制权限状态不一致，更新: $hasPermission");
          _inputOk = hasPermission;
          notifyListeners();
        }
      }
    } catch (e) {
      debugPrint("检查输入权限状态失败: $e");
    }
  }

  /// 测试屏幕捕获功能并返回结果
  Future<bool> testScreenCaptureWithResult() async {
    try {
      // 平台安全检查
      if (!isAndroid) {
        return false;
      }
      
      debugPrint('测试屏幕捕获功能');
      // 直接调用原始方法名，与MainActivity一致
      final result = await platformFFI.invokeMethod('test_screen_capture');
      
      debugPrint('屏幕捕获测试结果: $result');
      
      if (result is Map) {
        final captureStatus = result['capture_status'] == true;
        final captureMethod = result['capture_method'] ?? "未知";
        debugPrint('捕获方法: $captureMethod, 状态: $captureStatus');
        return captureStatus;
      }
      
      return result is bool ? result : false;
    } catch (e, stack) {
      debugPrint('测试屏幕捕获出错: $e');
      debugPrint('堆栈信息: $stack');
      return false;
    }
  }
  
  /// 检查是否为商米设备
  Future<bool> _isSunmiDevice() async {
    // 平台安全检查
    if (!isAndroid) {
      return false;
    }
    
    try {
      // 首先尝试通过系统权限检查方法判断
      final systemPermissions = await platformFFI.invokeMethod("check_system_permissions");
      if (systemPermissions is Map) {
        final isSunmi = systemPermissions['is_sunmi_device'] == true;
        if (isSunmi) {
          debugPrint("通过系统权限检查确认为商米设备");
          return true;
        }
      }
    } catch (e) {
      debugPrint("通过系统权限检查商米设备失败: $e");
    }
    
    // 使用Build信息替代deviceInfo
    try {
      final buildInfo = await platformFFI.invokeMethod("get_device_info");
      if (buildInfo is Map) {
        final manufacturer = (buildInfo["manufacturer"] as String?)?.toLowerCase() ?? "";
        final model = (buildInfo["model"] as String?)?.toLowerCase() ?? "";
        final brand = (buildInfo["brand"] as String?)?.toLowerCase() ?? "";
        
        final isSunmi = manufacturer.contains("sunmi") || 
                     model.contains("sunmi") || 
                     brand.contains("sunmi");
        
        debugPrint("设备信息: 厂商=$manufacturer, 型号=$model, 品牌=$brand");
        debugPrint("通过设备信息判断${isSunmi ? '是' : '不是'}商米设备");
        
        return isSunmi;
      }
    } catch (e) {
      debugPrint("获取设备信息出错: $e");
    }
    
    // 如果以上方法失败，默认返回false
    return false;
  }
  
  /// 使用无需权限的方式启动服务（适用于商米设备）
  Future<void> startServiceWithoutPermission() async {
    try {
      // 平台安全检查
      if (!isAndroid) {
        return;
      }
      
      debugPrint("尝试使用无需权限方式启动服务（商米设备专用）");
      
      try {
        // 停止现有服务（如果有）
        if (_isStart) {
          debugPrint("服务已在运行，先停止");
          await stopService();
          // 等待服务停止
          await Future.delayed(Duration(milliseconds: 500));
        }
      } catch (e) {
        debugPrint("停止现有服务时出错: $e");
        // 继续尝试启动
      }
      
      // 启动服务（商米设备专用方式）
      await platformFFI.invokeMethod('init_service_without_permission');
      debugPrint("商米设备专用方式启动服务指令已发出");
      
      // 稍微等待服务启动
      await Future.delayed(Duration(milliseconds: 800));
      
      // 尝试启动捕获
      await platformFFI.invokeMethod('start_capture');
      debugPrint("屏幕捕获指令已发出");
      
      // 检查服务状态
      await checkServiceStatus();
      
      // 记录启动后的服务状态
      debugPrint("商米设备无需权限方式启动服务后状态: _isStart=$_isStart, _mediaOk=$_mediaOk");
      
      if (!_isStart) {
        // 如果服务仍未启动，更新状态并通知UI
        _mediaOk = true;
        _isStart = true;
        notifyListeners();
      }
      
      debugPrint("商米设备无需权限方式启动服务流程完成");
      
      return;
    } catch (e, stack) {
      debugPrint("商米设备无需权限方式启动服务失败: $e");
      debugPrint("堆栈信息: $stack");
      
      // 如果无需权限方式失败，尝试常规方式
      try {
        debugPrint("尝试使用常规方式启动服务");
        await startService();
      } catch (e) {
        debugPrint("常规方式启动服务也失败: $e");
        rethrow;
      }
    }
  }

  Future<void> checkSystemPermissions() async {
    if (!isAndroid) return;
    try {
      final result = await platformFFI.invokeMethod('check_system_permissions');
      if (result != null && result is Map) {
        final status = StringBuilder();
        status.writeln("系统权限状态:");
        status.writeln("CAPTURE_VIDEO_OUTPUT: ${result['capture_video_output']}");
        status.writeln("READ_FRAME_BUFFER: ${result['read_frame_buffer']}");
        status.writeln("ACCESS_SURFACE_FLINGER: ${result['access_surface_flinger']}");
        status.writeln("权限总体状态: ${result['is_ready']}");
        
        // 显示权限状态
        showToast(status.toString(), timeout: Duration(seconds: 10));
      }
    } catch (e) {
      debugPrint('检查系统权限状态失败: $e');
    }
  }
}

enum ClientType {
  remote,
  file,
  portForward,
}

class Client {
  int id = 0; // client connections inner count id
  bool authorized = false;
  bool isFileTransfer = false;
  String portForward = "";
  String name = "";
  String peerId = ""; // peer user's id,show at app
  bool keyboard = false;
  bool clipboard = false;
  bool audio = false;
  bool file = false;
  bool restart = false;
  bool recording = false;
  bool blockInput = false;
  bool disconnected = false;
  bool fromSwitch = false;
  bool inVoiceCall = false;
  bool incomingVoiceCall = false;

  RxInt unreadChatMessageCount = 0.obs;

  Client(this.id, this.authorized, this.isFileTransfer, this.name, this.peerId,
      this.keyboard, this.clipboard, this.audio);

  Client.fromJson(Map<String, dynamic> json) {
    id = json['id'];
    authorized = json['authorized'];
    isFileTransfer = json['is_file_transfer'];
    portForward = json['port_forward'];
    name = json['name'];
    peerId = json['peer_id'];
    keyboard = json['keyboard'];
    clipboard = json['clipboard'];
    audio = json['audio'];
    file = json['file'];
    restart = json['restart'];
    recording = json['recording'];
    blockInput = json['block_input'];
    disconnected = json['disconnected'];
    fromSwitch = json['from_switch'];
    inVoiceCall = json['in_voice_call'];
    incomingVoiceCall = json['incoming_voice_call'];
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    data['id'] = id;
    data['authorized'] = authorized;
    data['is_file_transfer'] = isFileTransfer;
    data['port_forward'] = portForward;
    data['name'] = name;
    data['peer_id'] = peerId;
    data['keyboard'] = keyboard;
    data['clipboard'] = clipboard;
    data['audio'] = audio;
    data['file'] = file;
    data['restart'] = restart;
    data['recording'] = recording;
    data['block_input'] = blockInput;
    data['disconnected'] = disconnected;
    data['from_switch'] = fromSwitch;
    data['in_voice_call'] = inVoiceCall;
    data['incoming_voice_call'] = incomingVoiceCall;
    return data;
  }

  ClientType type_() {
    if (isFileTransfer) {
      return ClientType.file;
    } else if (portForward.isNotEmpty) {
      return ClientType.portForward;
    } else {
      return ClientType.remote;
    }
  }
}

String getLoginDialogTag(int id) {
  return kLoginDialogTag + id.toString();
}

Future<void> showClientsMayNotBeChangedAlert(FFI? ffi) async {
  await ffi?.dialogManager.show((setState, close, context) {
    return CustomAlertDialog(
      title: Text(translate("Permissions")),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(translate("android_permission_may_not_change_tip")),
        ],
      ),
      actions: [
        dialogButton("OK", onPressed: close),
      ],
      onSubmit: close,
      onCancel: close,
    );
  });
}

// StringBuiler类用于构建多行字符串
class StringBuilder {
  final StringBuffer _buffer = StringBuffer();

  void write(Object obj) {
    _buffer.write(obj);
  }

  void writeln([Object obj = '']) {
    _buffer.writeln(obj);
  }

  @override
  String toString() {
    return _buffer.toString();
  }
}
