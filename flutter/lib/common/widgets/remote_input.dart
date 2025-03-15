import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/gestures.dart';

import 'package:flutter_hbb/models/platform_model.dart';
import 'package:flutter_hbb/common.dart';
import 'package:flutter_hbb/consts.dart';
import 'package:flutter_hbb/models/model.dart';
import 'package:flutter_hbb/models/input_model.dart';

import './gestures.dart';

class RawKeyFocusScope extends StatelessWidget {
  final FocusNode? focusNode;
  final ValueChanged<bool>? onFocusChange;
  final InputModel inputModel;
  final Widget child;

  RawKeyFocusScope({
    this.focusNode,
    this.onFocusChange,
    required this.inputModel,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    // https://github.com/flutter/flutter/issues/154053
    final useRawKeyEvents = isLinux && !isWeb;
    // FIXME: On Windows, `AltGr` will generate `Alt` and `Control` key events,
    // while `Alt` and `Control` are seperated key events for en-US input method.
    return FocusScope(
        autofocus: true,
        child: Focus(
            autofocus: true,
            canRequestFocus: true,
            focusNode: focusNode,
            onFocusChange: onFocusChange,
            onKey: useRawKeyEvents
                ? (FocusNode data, RawKeyEvent event) =>
                    inputModel.handleRawKeyEvent(event)
                : null,
            onKeyEvent: useRawKeyEvents
                ? null
                : (FocusNode node, KeyEvent event) =>
                    inputModel.handleKeyEvent(event),
            child: child));
  }
}

class RawTouchGestureDetectorRegion extends StatefulWidget {
  final Widget child;
  final FFI ffi;

  late final InputModel inputModel = ffi.inputModel;
  late final FfiModel ffiModel = ffi.ffiModel;

  RawTouchGestureDetectorRegion({
    required this.child,
    required this.ffi,
  });

  @override
  State<RawTouchGestureDetectorRegion> createState() =>
      _RawTouchGestureDetectorRegionState();
}

/// touchMode only:
///   LongPress -> right click
///   OneFingerPan -> start/end -> left down start/end
///   onDoubleTapDown -> move to
///   onLongPressDown => move to
///
/// mouseMode only:
///   DoubleFiner -> right click
///   HoldDrag -> left drag
class _RawTouchGestureDetectorRegionState
    extends State<RawTouchGestureDetectorRegion> {
  Offset _cacheLongPressPosition = Offset(0, 0);
  // Timestamp of the last long press event.
  int _cacheLongPressPositionTs = 0;
  double _mouseScrollIntegral = 0; // mouse scroll speed controller
  double _scale = 1;

  // Workaround tap down event when two fingers are used to scale(mobile)
  TapDownDetails? _lastTapDownDetails;

  PointerDeviceKind? lastDeviceKind;

  // For touch mode, onDoubleTap
  // `onDoubleTap()` does not provide the position of the tap event.
  Offset _lastPosOfDoubleTapDown = Offset.zero;
  bool _touchModePanStarted = false;
  Offset _doubleFinerTapPosition = Offset.zero;

  // 添加防重复触发变量
  bool _isTapHandled = false;
  DateTime _lastTapTime = DateTime.now();
  
  // 添加系统区域识别
  final _systemButtonRegion = Rect.fromLTRB(0, 0, 0, 0);
  // 添加事件时间戳映射，用于防止重复事件
  final Map<String, DateTime> _eventTimestamps = {};
  // 添加应用图标区域识别
  final _appIconRegion = Rect.fromLTRB(0, 0, 0, 0);
  
  // 识别不同区域
  bool _isInSystemRegion(Offset position) => _systemButtonRegion.contains(position);
  bool _isInAppIconRegion(Offset position) => _appIconRegion.contains(position);

  FFI get ffi => widget.ffi;
  FfiModel get ffiModel => widget.ffiModel;
  InputModel get inputModel => widget.inputModel;
  bool get handleTouch => (isDesktop || isWebDesktop) || ffiModel.touchMode;
  SessionID get sessionId => ffi.sessionId;

  @override
  void initState() {
    super.initState();
    // 初始化系统按钮区域检测
    // 对于大多数安卓设备，底部导航栏高度约为48dp
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final size = MediaQuery.of(context).size;
      final bottomHeight = 80.0; // 预留足够高度覆盖底部导航栏
      setState(() {
        _systemButtonRegion = Rect.fromLTRB(
          0, 
          size.height - bottomHeight,
          size.width,
          size.height
        );
        
        // 初始化应用图标区域（通常在屏幕上部）
        _appIconRegion = Rect.fromLTRB(
          0,
          0,
          size.width,
          size.height / 3 // 屏幕上三分之一区域
        );
        
        debugPrint("系统按钮区域初始化: $_systemButtonRegion");
        debugPrint("应用图标区域初始化: $_appIconRegion");
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return RawGestureDetector(
      child: widget.child,
      gestures: makeGestures(context),
    );
  }

  onTapDown(TapDownDetails d) async {
    lastDeviceKind = d.kind;
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    // 重置点击处理状态
    _isTapHandled = false;
    
    // 清理超过1秒的事件时间戳记录
    final now = DateTime.now();
    _eventTimestamps.removeWhere((key, time) => 
        now.difference(time).inMilliseconds > 1000);
    
    // 添加调试日志
    if (isMobile && ffiModel.isPeerAndroid) {
      final inSystemRegion = _isInSystemRegion(d.localPosition);
      final inAppIconRegion = _isInAppIconRegion(d.localPosition);
      debugPrint("[移动-安卓控制] 触发onTapDown事件: ${d.localPosition}, 在系统区域: $inSystemRegion, 在应用图标区域: $inAppIconRegion");
    }
    
    if (handleTouch) {
      _lastPosOfDoubleTapDown = d.localPosition;
      // Desktop or mobile "Touch mode"
      _lastTapDownDetails = d;
    }
  }

  onTapUp(TapUpDetails d) async {
    final TapDownDetails? lastTapDownDetails = _lastTapDownDetails;
    _lastTapDownDetails = null;
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    
    // 检查是否是移动设备控制安卓端的情况
    bool isMobileToAndroid = isMobile && ffiModel.isPeerAndroid;
    bool inSystemRegion = false;
    bool inAppIconRegion = false;
    
    // 记录事件时间戳和位置
    String eventKey = "${d.localPosition.dx.toInt()}_${d.localPosition.dy.toInt()}";
    _eventTimestamps[eventKey] = DateTime.now();
    
    // 添加调试日志
    if (isMobileToAndroid) {
      inSystemRegion = _isInSystemRegion(d.localPosition);
      inAppIconRegion = _isInAppIconRegion(d.localPosition);
      debugPrint("[移动-安卓控制] 触发onTapUp事件: ${d.localPosition}, 处理状态:${_isTapHandled}, 在系统区域: $inSystemRegion, 在应用图标区域: $inAppIconRegion");
    }
    
    if (handleTouch) {
      // 避免短时间内多次处理同一个点击
      if (_isTapHandled) {
        debugPrint("[移动-安卓控制] 跳过重复的onTapUp事件");
        return;
      }
      
      final isMoved =
          await ffi.cursorModel.move(d.localPosition.dx, d.localPosition.dy);
      if (isMoved) {
        if (lastTapDownDetails != null) {
          await inputModel.tapDown(MouseButtons.left);
        }
        await inputModel.tapUp(MouseButtons.left);
        
        // 标记点击已处理
        _isTapHandled = true;
        
        // 如果是移动设备控制安卓，延长防重复时间
        if (isMobileToAndroid) {
          // 对不同区域做特殊处理，使用不同的延迟
          int delay = 300; // 默认延迟
          
          if (inSystemRegion) {
            delay = 800; // 系统导航区域延长防重复时间
            debugPrint("[移动-安卓控制] 系统区域点击，使用延长延迟: $delay ms");
          } else if (inAppIconRegion) {
            delay = 400; // 应用图标区域使用中等延迟
            debugPrint("[移动-安卓控制] 应用图标区域点击，使用中等延迟: $delay ms");
          }
          
          // 延迟清除状态，防止onTap又触发一次
          Future.delayed(Duration(milliseconds: delay), () {
            _isTapHandled = false;
            debugPrint("[移动-安卓控制] 重置点击处理状态");
          });
        }
      }
    }
  }

  onTap() async {
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    
    // 检查是否是移动设备控制安卓端的情况
    bool isMobileToAndroid = isMobile && ffiModel.isPeerAndroid;
    
    // 添加调试日志
    if (isMobileToAndroid) {
      debugPrint("[移动-安卓控制] 触发onTap事件，时间差: ${DateTime.now().difference(_lastTapTime).inMilliseconds}ms, 处理状态:${_isTapHandled}");
    }
    
    // 系统区域特殊处理 - 完全禁止onTap处理
    if (_isInSystemRegion(_lastPosOfDoubleTapDown)) {
      debugPrint("[移动-安卓控制] 系统区域点击，跳过onTap事件处理");
      return;
    }
    
    // 检查是否有近期相似位置的点击
    bool hasDuplicate = false;
    _eventTimestamps.forEach((key, time) {
      if (DateTime.now().difference(time).inMilliseconds < 300) {
        hasDuplicate = true;
      }
    });
    
    // 防重复点击检查
    DateTime now = DateTime.now();
    if (now.difference(_lastTapTime).inMilliseconds < 300 || _isTapHandled || hasDuplicate) {
      debugPrint("[移动-安卓控制] 跳过重复的onTap事件");
      return;
    }
    _lastTapTime = now;
    
    if (!handleTouch) {
      // Mobile, "Mouse mode"
      if (isMobileToAndroid) {
        // 移动设备控制安卓时，仅在Mouse模式下才发送点击，Touch模式已经在onTapUp处理过了
        if (!ffiModel.touchMode) {
          debugPrint("[移动-安卓控制] 鼠标模式下发送点击");
          
          // 检查是否在应用图标区域
          bool inAppIconRegion = _isInAppIconRegion(_lastPosOfDoubleTapDown);
          if (inAppIconRegion) {
            debugPrint("[移动-安卓控制] 应用图标区域点击");
            // 对于应用图标，确保发送明确的点击事件，不要使用手势
            await inputModel.tapDown(MouseButtons.left);
            // 短暂延迟后发送UP事件
            await Future.delayed(Duration(milliseconds: 50));
            await inputModel.tapUp(MouseButtons.left);
          } else {
            await inputModel.tap(MouseButtons.left);
          }
          
          _isTapHandled = true;
          // 延迟清除状态
          Future.delayed(Duration(milliseconds: 500), () {
            _isTapHandled = false;
            debugPrint("[移动-安卓控制] 点击状态重置");
          });
        } else {
          debugPrint("[移动-安卓控制] 触摸模式下跳过点击（已在onTapUp处理）");
        }
      } else {
        // 其他平台组合，保持原有行为
        await inputModel.tap(MouseButtons.left);
      }
    }
  }

  onDoubleTapDown(TapDownDetails d) async {
    lastDeviceKind = d.kind;
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (handleTouch) {
      _lastPosOfDoubleTapDown = d.localPosition;
      await ffi.cursorModel.move(d.localPosition.dx, d.localPosition.dy);
    }
  }

  onDoubleTap() async {
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    
    // 检查是否是移动设备控制安卓的情况
    bool isMobileToAndroid = isMobile && ffiModel.isPeerAndroid;
    
    // 添加调试日志
    if (isMobileToAndroid) {
      debugPrint("[移动-安卓控制] 触发onDoubleTap事件");
    }
    
    // 双击时，我们想确保这是真正的双击意图，而不是单击事件错误触发
    if (isMobileToAndroid) {
      // 在移动端控制安卓时，不处理双击，避免意外触发
      // 双击的语义会被转换为两次单击来传递
      debugPrint("[移动-安卓控制] 移动设备控制安卓时，跳过双击事件转发");
      return;
    }
    
    if (ffiModel.touchMode && ffi.cursorModel.lastIsBlocked) {
      return;
    }
    if (handleTouch &&
        !ffi.cursorModel.isInRemoteRect(_lastPosOfDoubleTapDown)) {
      return;
    }
    
    // 对于非安卓目标，保持原有双击行为
    await inputModel.tap(MouseButtons.left);
    await inputModel.tap(MouseButtons.left);
  }

  onLongPressDown(LongPressDownDetails d) async {
    lastDeviceKind = d.kind;
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (handleTouch) {
      _lastPosOfDoubleTapDown = d.localPosition;
      _cacheLongPressPosition = d.localPosition;
      if (!ffi.cursorModel.isInRemoteRect(d.localPosition)) {
        return;
      }
      _cacheLongPressPositionTs = DateTime.now().millisecondsSinceEpoch;
      if (ffiModel.isPeerMobile) {
        await ffi.cursorModel
            .move(_cacheLongPressPosition.dx, _cacheLongPressPosition.dy);
        await inputModel.tapDown(MouseButtons.left);
      }
    }
  }

  onLongPressUp() async {
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (handleTouch) {
      await inputModel.tapUp(MouseButtons.left);
    }
  }

  // for mobiles
  onLongPress() async {
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (!ffi.ffiModel.isPeerMobile) {
      if (handleTouch) {
        final isMoved = await ffi.cursorModel
            .move(_cacheLongPressPosition.dx, _cacheLongPressPosition.dy);
        if (!isMoved) {
          return;
        }
      }
      await inputModel.tap(MouseButtons.right);
    } else {
      // It's better to send a message to tell the controlled device that the long press event is triggered.
      // We're now using a `TimerTask` in `InputService.kt` to decide whether to trigger the long press event.
      // It's not accurate and it's better to use the same detection logic in the controlling side.
    }
  }

  onLongPressMoveUpdate(LongPressMoveUpdateDetails d) async {
    if (!ffiModel.isPeerMobile || lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (handleTouch) {
      if (!ffi.cursorModel.isInRemoteRect(d.localPosition)) {
        return;
      }
      await ffi.cursorModel.move(d.localPosition.dx, d.localPosition.dy);
    }
  }

  onDoubleFinerTapDown(TapDownDetails d) async {
    lastDeviceKind = d.kind;
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    _doubleFinerTapPosition = d.localPosition;
    // ignore for desktop and mobile
  }

  onDoubleFinerTap(TapDownDetails d) async {
    lastDeviceKind = d.kind;
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }

    // mobile mouse mode or desktop touch screen
    final isMobileMouseMode = isMobile && !ffiModel.touchMode;
    // We can't use `d.localPosition` here because it's always (0, 0) on desktop.
    final isDesktopInRemoteRect = (isDesktop || isWebDesktop) &&
        ffi.cursorModel.isInRemoteRect(_doubleFinerTapPosition);
    if (isMobileMouseMode || isDesktopInRemoteRect) {
      await inputModel.tap(MouseButtons.right);
    }
  }

  onHoldDragStart(DragStartDetails d) async {
    lastDeviceKind = d.kind;
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (!handleTouch) {
      await inputModel.sendMouse('down', MouseButtons.left);
    }
  }

  onHoldDragUpdate(DragUpdateDetails d) async {
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (!handleTouch) {
      await ffi.cursorModel.updatePan(d.delta, d.localPosition, handleTouch);
    }
  }

  onHoldDragEnd(DragEndDetails d) async {
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (!handleTouch) {
      await inputModel.sendMouse('up', MouseButtons.left);
    }
  }

  onOneFingerPanStart(BuildContext context, DragStartDetails d) async {
    final TapDownDetails? lastTapDownDetails = _lastTapDownDetails;
    _lastTapDownDetails = null;
    lastDeviceKind = d.kind ?? lastDeviceKind;
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (handleTouch) {
      if (lastTapDownDetails != null) {
        await ffi.cursorModel.move(lastTapDownDetails.localPosition.dx,
            lastTapDownDetails.localPosition.dy);
      }
      if (ffi.cursorModel.shouldBlock(d.localPosition.dx, d.localPosition.dy)) {
        return;
      }
      if (!ffi.cursorModel.isInRemoteRect(d.localPosition)) {
        return;
      }

      _touchModePanStarted = true;
      if (isDesktop || isWebDesktop) {
        ffi.cursorModel.trySetRemoteWindowCoords();
      }

      // Workaround for the issue that the first pan event is sent a long time after the start event.
      // If the time interval between the start event and the first pan event is less than 500ms,
      // we consider to use the long press position as the start position.
      //
      // TODO: We should find a better way to send the first pan event as soon as possible.
      if (DateTime.now().millisecondsSinceEpoch - _cacheLongPressPositionTs <
          500) {
        await ffi.cursorModel
            .move(_cacheLongPressPosition.dx, _cacheLongPressPosition.dy);
      }
      await inputModel.sendMouse('down', MouseButtons.left);
      await ffi.cursorModel.move(d.localPosition.dx, d.localPosition.dy);
    } else {
      final offset = ffi.cursorModel.offset;
      final cursorX = offset.dx;
      final cursorY = offset.dy;
      final visible =
          ffi.cursorModel.getVisibleRect().inflate(1); // extend edges
      final size = MediaQueryData.fromView(View.of(context)).size;
      if (!visible.contains(Offset(cursorX, cursorY))) {
        await ffi.cursorModel.move(size.width / 2, size.height / 2);
      }
    }
  }

  onOneFingerPanUpdate(DragUpdateDetails d) async {
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (ffi.cursorModel.shouldBlock(d.localPosition.dx, d.localPosition.dy)) {
      return;
    }
    if (handleTouch && !_touchModePanStarted) {
      return;
    }
    await ffi.cursorModel.updatePan(d.delta, d.localPosition, handleTouch);
  }

  onOneFingerPanEnd(DragEndDetails d) async {
    _touchModePanStarted = false;
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if (isDesktop || isWebDesktop) {
      ffi.cursorModel.clearRemoteWindowCoords();
    }
    if (handleTouch) {
      await inputModel.sendMouse('up', MouseButtons.left);
    }
  }

  // scale + pan event
  onTwoFingerScaleStart(ScaleStartDetails d) {
    _lastTapDownDetails = null;
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
  }

  onTwoFingerScaleUpdate(ScaleUpdateDetails d) async {
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if ((isDesktop || isWebDesktop)) {
      final scale = ((d.scale - _scale) * 1000).toInt();
      _scale = d.scale;

      if (scale != 0) {
        await bind.sessionSendPointer(
            sessionId: sessionId,
            msg: json.encode(
                PointerEventToRust(kPointerEventKindTouch, 'scale', scale)
                    .toJson()));
      }
    } else {
      // mobile
      ffi.canvasModel.updateScale(d.scale / _scale, d.focalPoint);
      _scale = d.scale;
      ffi.canvasModel.panX(d.focalPointDelta.dx);
      ffi.canvasModel.panY(d.focalPointDelta.dy);
    }
  }

  onTwoFingerScaleEnd(ScaleEndDetails d) async {
    if (lastDeviceKind != PointerDeviceKind.touch) {
      return;
    }
    if ((isDesktop || isWebDesktop)) {
      await bind.sessionSendPointer(
          sessionId: sessionId,
          msg: json.encode(
              PointerEventToRust(kPointerEventKindTouch, 'scale', 0).toJson()));
    } else {
      // mobile
      _scale = 1;
      // No idea why we need to set the view style to "" here.
      // bind.sessionSetViewStyle(sessionId: sessionId, value: "");
    }
    await inputModel.sendMouse('up', MouseButtons.left);
  }

  get onHoldDragCancel => null;
  get onThreeFingerVerticalDragUpdate => ffi.ffiModel.isPeerAndroid
      ? null
      : (d) {
          _mouseScrollIntegral += d.delta.dy / 4;
          if (_mouseScrollIntegral > 1) {
            inputModel.scroll(1);
            _mouseScrollIntegral = 0;
          } else if (_mouseScrollIntegral < -1) {
            inputModel.scroll(-1);
            _mouseScrollIntegral = 0;
          }
        };

  makeGestures(BuildContext context) {
    return <Type, GestureRecognizerFactory>{
      // Official
      TapGestureRecognizer:
          GestureRecognizerFactoryWithHandlers<TapGestureRecognizer>(
              () => TapGestureRecognizer(), (instance) {
        instance
          ..onTapDown = onTapDown
          ..onTapUp = onTapUp
          ..onTap = onTap;
      }),
      DoubleTapGestureRecognizer:
          GestureRecognizerFactoryWithHandlers<DoubleTapGestureRecognizer>(
              () => DoubleTapGestureRecognizer(), (instance) {
        instance
          ..onDoubleTapDown = onDoubleTapDown
          ..onDoubleTap = onDoubleTap;
      }),
      LongPressGestureRecognizer:
          GestureRecognizerFactoryWithHandlers<LongPressGestureRecognizer>(
              () => LongPressGestureRecognizer(), (instance) {
        instance
          ..onLongPressDown = onLongPressDown
          ..onLongPressUp = onLongPressUp
          ..onLongPress = onLongPress
          ..onLongPressMoveUpdate = onLongPressMoveUpdate;
      }),
      // Customized
      HoldTapMoveGestureRecognizer:
          GestureRecognizerFactoryWithHandlers<HoldTapMoveGestureRecognizer>(
              () => HoldTapMoveGestureRecognizer(),
              (instance) => instance
                ..onHoldDragStart = onHoldDragStart
                ..onHoldDragUpdate = onHoldDragUpdate
                ..onHoldDragCancel = onHoldDragCancel
                ..onHoldDragEnd = onHoldDragEnd),
      DoubleFinerTapGestureRecognizer:
          GestureRecognizerFactoryWithHandlers<DoubleFinerTapGestureRecognizer>(
              () => DoubleFinerTapGestureRecognizer(), (instance) {
        instance
          ..onDoubleFinerTap = onDoubleFinerTap
          ..onDoubleFinerTapDown = onDoubleFinerTapDown;
      }),
      CustomTouchGestureRecognizer:
          GestureRecognizerFactoryWithHandlers<CustomTouchGestureRecognizer>(
              () => CustomTouchGestureRecognizer(), (instance) {
        instance.onOneFingerPanStart =
            (DragStartDetails d) => onOneFingerPanStart(context, d);
        instance
          ..onOneFingerPanUpdate = onOneFingerPanUpdate
          ..onOneFingerPanEnd = onOneFingerPanEnd
          ..onTwoFingerScaleStart = onTwoFingerScaleStart
          ..onTwoFingerScaleUpdate = onTwoFingerScaleUpdate
          ..onTwoFingerScaleEnd = onTwoFingerScaleEnd
          ..onThreeFingerVerticalDragUpdate = onThreeFingerVerticalDragUpdate;
      }),
    };
  }
}

class RawPointerMouseRegion extends StatelessWidget {
  final InputModel inputModel;
  final Widget child;
  final MouseCursor? cursor;
  final PointerEnterEventListener? onEnter;
  final PointerExitEventListener? onExit;
  final PointerDownEventListener? onPointerDown;
  final PointerUpEventListener? onPointerUp;

  RawPointerMouseRegion({
    this.onEnter,
    this.onExit,
    this.cursor,
    this.onPointerDown,
    this.onPointerUp,
    required this.inputModel,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerHover: inputModel.onPointHoverImage,
      onPointerDown: (evt) {
        onPointerDown?.call(evt);
        inputModel.onPointDownImage(evt);
      },
      onPointerUp: (evt) {
        onPointerUp?.call(evt);
        inputModel.onPointUpImage(evt);
      },
      onPointerMove: inputModel.onPointMoveImage,
      onPointerSignal: inputModel.onPointerSignalImage,
      onPointerPanZoomStart: inputModel.onPointerPanZoomStart,
      onPointerPanZoomUpdate: inputModel.onPointerPanZoomUpdate,
      onPointerPanZoomEnd: inputModel.onPointerPanZoomEnd,
      child: MouseRegion(
        cursor: inputModel.isViewOnly
            ? MouseCursor.defer
            : (cursor ?? MouseCursor.defer),
        onEnter: onEnter,
        onExit: onExit,
        child: child,
      ),
    );
  }
}
