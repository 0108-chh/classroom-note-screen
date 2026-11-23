"""配对与主题派生规则（Windows / Android 被控端 / Android 控制端 必须完全一致）。

设备号 6 位 + 配对密钥 10 位，二者共同决定一个不可猜的信道：
    channel = sha256("DEVICE:KEY").hexdigest()[:20]
    topic   = ns1/<channel>/<in|st|ack>

配对串（二维码内容）：
    NS1|<设备号>|<密钥>|<base32 设备名>
"""
from __future__ import annotations

import base64
import hashlib
import io
import secrets

ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # 去掉 I O 0 1，便于口头报读
PAIR_PREFIX = "NS1"


def rand_code(n: int) -> str:
    return "".join(secrets.choice(ALPHABET) for _ in range(n))


def new_identity() -> tuple[str, str]:
    return rand_code(6), rand_code(10)


def channel_id(device_id: str, device_key: str) -> str:
    raw = f"{device_id.strip().upper()}:{device_key.strip().upper()}".encode()
    return hashlib.sha256(raw).hexdigest()[:20]


def topic_of(device_id: str, device_key: str, kind: str) -> str:
    from config import TOPIC_ROOT
    return f"{TOPIC_ROOT}/{channel_id(device_id, device_key)}/{kind}"


def topic_root_of(device_id: str, device_key: str) -> str:
    from config import TOPIC_ROOT
    return f"{TOPIC_ROOT}/{channel_id(device_id, device_key)}"


def encode_name(name: str) -> str:
    return base64.b32encode(name.encode("utf-8")).decode().rstrip("=")


def decode_name(blob: str) -> str:
    pad = "=" * (-len(blob) % 8)
    try:
        return base64.b32decode(blob.upper() + pad).decode("utf-8")
    except Exception:
        return ""


def pairing_string(device_id: str, device_key: str, name: str) -> str:
    return f"{PAIR_PREFIX}|{device_id}|{device_key}|{encode_name(name)}"


def parse_pairing_string(text: str) -> tuple[str, str, str] | None:
    parts = (text or "").strip().split("|")
    if len(parts) < 3 or parts[0] != PAIR_PREFIX:
        return None
    dev, key = parts[1].upper(), parts[2].upper()
    name = decode_name(parts[3]) if len(parts) > 3 else ""
    if len(dev) < 4 or len(key) < 6:
        return None
    return dev, key, name


def qr_png(text: str, size: int = 420, dark: str = "#1b2130") -> bytes:
    import qrcode
    from PIL import Image

    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2)
    qr.add_data(text)
    qr.make(fit=True)
    img = qr.make_image(fill_color=dark, back_color="white").convert("RGB")
    box = max(1, size // img.size[0])
    img = img.resize((img.size[0] * box, img.size[1] * box), Image.NEAREST)
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def qr_data_url(text: str, size: int = 420) -> str:
    return "data:image/png;base64," + base64.b64encode(qr_png(text, size)).decode()
