from PyQt5.QtWidgets import QApplication, QWidget, QTextEdit, QVBoxLayout, QHBoxLayout
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject, QPoint, QRect
import sys
import threading

_app = None
_win = None
_pending_texts = []
_initial_opacity = 0.95
_min_opacity = 0.25
_fade_step = 0.05
_fade_interval_ms = 300
_idle_delay_ms = 3000
_resize_margin = 8
_min_width = 200
_min_height = 80

class _TextSignal(QObject):
    sig = pyqtSignal(str)

_signal = _TextSignal()

class FloatingWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowFlags(Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self._current_opacity = _initial_opacity
        self.setWindowOpacity(self._current_opacity)
        self._drag_offset = None
        self._fade_timer = QTimer(self)
        self._fade_timer.setInterval(_fade_interval_ms)
        self._fade_timer.timeout.connect(self._fade_step)
        self._idle_timer = QTimer(self)
        self._idle_timer.setSingleShot(True)
        self._idle_timer.timeout.connect(self._start_fade)
        self._idle_timer.start(_idle_delay_ms)
        self._resizing = False
        self._resize_dir = ()
        self._press_pos = None
        self._press_geom = None
        self.setMouseTracking(True)
        self._init_ui()
        _signal.sig.connect(self.receive_text)

    def _init_ui(self):
        self.resize(420, 200)
        self.setMinimumSize(_min_width, _min_height)
        self.drag_bar = QWidget(self)
        self.drag_bar.setFixedHeight(24)
        self.drag_bar.setCursor(Qt.SizeAllCursor)
        self.drag_bar.mousePressEvent = self._drag_mouse_press
        self.drag_bar.mouseMoveEvent = self._drag_mouse_move
        self.drag_bar.mouseReleaseEvent = self._drag_mouse_release
        self.text = QTextEdit(self)
        self.text.setReadOnly(True)
        self.text.setAcceptRichText(False)
        self.text.setVerticalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        self.text.setHorizontalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        self.text.installEventFilter(self)
        self.drag_bar.setStyleSheet("background: rgba(0,0,0,120); border-top-left-radius:8px; border-top-right-radius:8px;")
        self.text.setStyleSheet("background: rgba(0,0,0,120); color: white; border-bottom-left-radius:8px; border-bottom-right-radius:8px; padding:6px;")
        main_layout = QVBoxLayout(self)
        main_layout.setContentsMargins(0,0,0,0)
        main_layout.setSpacing(0)
        main_layout.addWidget(self.drag_bar)
        main_layout.addWidget(self.text)
        self.text.setMouseTracking(True)
        self.drag_bar.setMouseTracking(True)

    def eventFilter(self, obj, event):
        if obj is self.text:
            if event.type() in (event.MouseButtonPress, event.MouseButtonRelease, event.MouseMove, event.Wheel):
                self._reset_opacity_and_timer()
            if event.type() == event.KeyPress:
                self._reset_opacity_and_timer()
        if event.type() in (event.MouseButtonPress, event.MouseButtonRelease, event.MouseMove):
            if hasattr(event, "button"):
                local_pos = event.pos()
            else:
                local_pos = event.pos()
            if obj is not self:
                local_pos = obj.mapTo(self, local_pos)
            if event.type() == event.MouseButtonPress:
                if event.button() == Qt.LeftButton:
                    self._window_mouse_press(local_pos, event.globalPos())
            elif event.type() == event.MouseButtonRelease:
                if event.button() == Qt.LeftButton:
                    self._window_mouse_release(local_pos, event.globalPos())
            elif event.type() == event.MouseMove:
                self._window_mouse_move(local_pos, event.globalPos(), event.buttons())
        return super().eventFilter(obj, event)

    def _get_edges(self, p):
        w = self.width()
        h = self.height()
        x = p.x()
        y = p.y()
        left = x <= _resize_margin
        right = x >= w - _resize_margin
        top = y <= _resize_margin
        bottom = y >= h - _resize_margin
        dirs = ()
        if left:
            dirs += ("left",)
        if right:
            dirs += ("right",)
        if top:
            dirs += ("top",)
        if bottom:
            dirs += ("bottom",)
        return dirs

    def _update_cursor(self, p):
        dirs = self._get_edges(p)
        if ("left" in dirs and "top" in dirs) or ("right" in dirs and "bottom" in dirs):
            self.setCursor(Qt.SizeFDiagCursor)
        elif ("right" in dirs and "top" in dirs) or ("left" in dirs and "bottom" in dirs):
            self.setCursor(Qt.SizeBDiagCursor)
        elif "left" in dirs or "right" in dirs:
            self.setCursor(Qt.SizeHorCursor)
        elif "top" in dirs or "bottom" in dirs:
            self.setCursor(Qt.SizeVerCursor)
        else:
            self.setCursor(Qt.ArrowCursor)

    def _window_mouse_press(self, local_pos, global_pos):
        self._reset_opacity_and_timer()
        dirs = self._get_edges(local_pos)
        if dirs:
            self._resizing = True
            self._resize_dir = dirs
            self._press_pos = global_pos
            self._press_geom = self.geometry()
            return

    def _window_mouse_move(self, local_pos, global_pos, buttons):
        if self._resizing and self._press_pos is not None and self._press_geom is not None:
            dx = global_pos.x() - self._press_pos.x()
            dy = global_pos.y() - self._press_pos.y()
            geom = QRect(self._press_geom)
            if "left" in self._resize_dir:
                new_x = geom.x() + dx
                new_w = geom.width() - dx
                if new_w >= self.minimumWidth():
                    geom.setLeft(new_x)
                else:
                    geom.setLeft(geom.right() - self.minimumWidth() + 1)
            if "right" in self._resize_dir:
                new_w = geom.width() + dx
                if new_w >= self.minimumWidth():
                    geom.setRight(geom.left() + new_w - 1)
                else:
                    geom.setRight(geom.left() + self.minimumWidth() - 1)
            if "top" in self._resize_dir:
                new_y = geom.y() + dy
                new_h = geom.height() - dy
                if new_h >= self.minimumHeight():
                    geom.setTop(new_y)
                else:
                    geom.setTop(geom.bottom() - self.minimumHeight() + 1)
            if "bottom" in self._resize_dir:
                new_h = geom.height() + dy
                if new_h >= self.minimumHeight():
                    geom.setBottom(geom.top() + new_h - 1)
                else:
                    geom.setBottom(geom.top() + self.minimumHeight() - 1)
            self.setGeometry(geom)
            return
        else:
            self._update_cursor(local_pos)

    def _window_mouse_release(self, local_pos, global_pos):
        if self._resizing:
            self._resizing = False
            self._resize_dir = ()
            self._press_pos = None
            self._press_geom = None
            self.setCursor(Qt.ArrowCursor)

    def _drag_mouse_press(self, e):
        if e.button() == Qt.LeftButton:
            self._drag_offset = e.globalPos() - self.frameGeometry().topLeft()
            self._reset_opacity_and_timer()
            e.accept()

    def _drag_mouse_move(self, e):
        if self._drag_offset is not None and (e.buttons() & Qt.LeftButton):
            self.move(e.globalPos() - self._drag_offset)
            self._reset_opacity_and_timer()
            e.accept()

    def _drag_mouse_release(self, e):
        self._drag_offset = None
        self._reset_opacity_and_timer()
        e.accept()

    def receive_text(self, s: str):
        self._reset_opacity_and_timer()
        if self.text.toPlainText():
            self.text.append(s)
        else:
            self.text.setPlainText(s)
        cursor = self.text.textCursor()
        cursor.movePosition(cursor.End)
        self.text.setTextCursor(cursor)

    def _reset_opacity_and_timer(self):
        self._fade_timer.stop()
        self._current_opacity = _initial_opacity
        self.setWindowOpacity(self._current_opacity)
        self._idle_timer.start(_idle_delay_ms)

    def _start_fade(self):
        if self._current_opacity > _min_opacity + 1e-6:
            self._fade_timer.start()

    def _fade_step(self):
        self._current_opacity = max(_min_opacity, self._current_opacity - _fade_step)
        self.setWindowOpacity(self._current_opacity)
        if self._current_opacity <= _min_opacity + 1e-6:
            self._fade_timer.stop()

def _ensure_app_and_window():
    global _app, _win
    if _app is None:
        _app = QApplication.instance() or QApplication(sys.argv)
    if _win is None:
        _win = FloatingWindow()
        _win.show()
        for t in _pending_texts:
            _signal.sig.emit(t)
        _pending_texts.clear()

def create_floating_window():
    _ensure_app_and_window()
    try:
        _app.exec_()
    except Exception:
        pass

def send_text(s: str):
    if _win is None:
        _pending_texts.append(s)
        _ensure_app_and_window()
        return
    if threading.current_thread() is threading.main_thread():
        _signal.sig.emit(s)
    else:
        _signal.sig.emit(s)

if __name__ == "__main__":
    create_floating_window()
