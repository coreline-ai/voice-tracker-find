# @TASK PB-T3 - 주제 정규화 임베딩 층 (Ollama)
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_embed.py
"""로컬 임베딩(Ollama)으로 주제/claim 의 의미 벡터를 얻는다.

주제 정규화의 "의미 축"이다. 구조 축(scorer.py)과 하이브리드로 합쳐 쓴다.
임베딩 단독으로는 표면적 유사성에 흔들려 오탐이 난다(실측: '민준이-태권도'가
'투자'와 0.9). 그래서 이 모듈은 후보 점수의 한 축일 뿐, 단독 판단에 쓰지 않는다.

Ollama HTTP API(localhost:11434)를 부른다. torch/whisper 처럼 무거운 의존성이
아니라 상주 서버라, 이 모듈은 표준 라이브러리만 쓰고 서버가 없으면 명확히
실패한다.
"""

from __future__ import annotations

import json
import logging
import math
import urllib.error
import urllib.request
from collections.abc import Callable

logger = logging.getLogger(__name__)

DEFAULT_MODEL = "nomic-embed-text"
DEFAULT_HOST = "http://localhost:11434"

# 텍스트 하나를 벡터로 바꾸는 함수 타입. 파이프라인에 DI 로 주입해 테스트에서
# 가짜 임베더로 대체한다(실제 Ollama 없이 로직 검증).
EmbedFn = Callable[[str], list[float]]


class EmbedError(RuntimeError):
    """임베딩 서버 호출 실패."""


def load_embedder(
    model: str = DEFAULT_MODEL, host: str = DEFAULT_HOST, timeout: float = 30.0
) -> EmbedFn:
    """Ollama 임베딩 함수를 만든다.

    Args:
        model: 임베딩 모델 이름. 미리 `ollama pull` 되어 있어야 한다.
        host: Ollama 서버 주소.
        timeout: 요청 타임아웃(초).

    Returns:
        텍스트 -> 벡터 함수. 서버/모델 문제 시 :class:`EmbedError`.
    """
    url = f"{host.rstrip('/')}/api/embeddings"

    def embed(text: str) -> list[float]:
        body = json.dumps({"model": model, "prompt": text}).encode("utf-8")
        request = urllib.request.Request(  # noqa: S310 - 고정 로컬 호스트
            url, data=body, headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310
                payload = json.loads(response.read())
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise EmbedError(
                f"임베딩 서버 호출 실패({url}). Ollama 가 떠 있고 '{model}' 이 "
                f"pull 되어 있는지 확인하세요: {exc}"
            ) from exc
        vector = payload.get("embedding")
        if not vector:
            raise EmbedError(f"임베딩 응답에 벡터가 없습니다: {payload}")
        return vector

    return embed


def cosine(a: list[float], b: list[float]) -> float:
    """두 벡터의 코사인 유사도(-1~1). 한쪽이 0벡터면 0."""
    dot = sum(x * y for x, y in zip(a, b, strict=False))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)
