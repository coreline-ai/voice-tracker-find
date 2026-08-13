# @TASK PB-T6 - VAULT_INDEX 클러스터를 노트에 적용 (frontmatter cluster + sources 이동)
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_vault_apply.py
"""생성된 `_index/VAULT_INDEX.md` 의 클러스터 배정을 주제 노트에 반영한다.

핸드오프 §4-M 볼트 적용 2단계. 각 20-notes 노트에 대해:
  1) frontmatter 에 `cluster: <클러스터>` 를 주입한다(이미 있으면 교체).
  2) frontmatter 의 긴 `sources: [...]` 를 본문 맨 뒤 `## 출처` 섹션으로 옮긴다
     — 폰에서 노트 첫 화면이 sources 로 덮이는 문제(§0③) 해소.

핵심 함수 :func:`transform_note` 는 순수 함수라 여러 번 적용해도 결과가 같다:
sources 를 이미 옮긴 노트는 frontmatter 에 sources 가 없으므로 본문 `## 출처` 를
그대로 두고 cluster 값만 갱신한다.

⚠️ 파이프라인 연동은 별개다. merge_topics 는 sources 를 frontmatter 로 다시 쓰므로,
이 변환은 "지금 볼트"를 정리할 뿐 이후 그 주제에 새 항목이 들어오면 되돌려질 수
있다. 항구적 반영은 render_topic_note 를 바꾸는 별도 작업.
"""

from __future__ import annotations

import logging
import re
import sys
from pathlib import Path

from airvoice.topics import TOPICS_SUBDIR
from airvoice.vault_index import (
    FALLBACK_CLUSTER,
    INDEX_SUBDIR,
    VAULT_INDEX_FILENAME,
    _write_lf,
)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

logger = logging.getLogger(__name__)

_SOURCES_HEADING = "## 출처"
_FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n(.*)$", re.DOTALL)
_CLUSTER_HEADER_RE = re.compile(r"^## 클러스터: (.+?) \(\d+개\)$")


def parse_index_clusters(index_text: str) -> dict[str, str]:
    """VAULT_INDEX.md 본문에서 {주제 ID: 클러스터명} 매핑을 복원한다."""
    mapping: dict[str, str] = {}
    current: str | None = None
    for line in index_text.splitlines():
        header = _CLUSTER_HEADER_RE.match(line)
        if header:
            current = header.group(1)
            continue
        if current and line.startswith("| ") and not line.startswith("| ID"):
            cid = line.split("|")[1].strip()
            if cid:
                mapping[cid] = current
    return mapping


def _parse_sources_line(line: str) -> list[str]:
    """`sources: [a (d), b (d)]` 한 줄에서 소스 항목 목록을 뽑는다."""
    inner = line[len("sources:") :].strip()
    if inner.startswith("[") and inner.endswith("]"):
        inner = inner[1:-1].strip()
    return [s.strip() for s in inner.split(", ") if s.strip()]


def transform_note(content: str, cluster: str) -> str:
    """노트 1건에 cluster 를 주입하고 frontmatter 의 sources 를 본문 뒤로 옮긴다.

    frontmatter 가 없으면 안전하게 원본을 그대로 반환한다. 여러 번 적용해도
    결과가 같다(멱등).
    """
    match = _FRONTMATTER_RE.match(content)
    if not match:
        return content
    frontmatter, body = match.group(1), match.group(2)

    sources: list[str] | None = None
    kept: list[str] = []
    for line in frontmatter.split("\n"):
        if line.startswith("sources:") and sources is None:
            sources = _parse_sources_line(line)
            continue  # frontmatter 에서 제거
        if line.startswith("cluster:"):
            continue  # 기존 cluster 제거 후 아래서 재삽입
        kept.append(line)

    # cluster 를 date 줄 바로 뒤에(없으면 맨 끝에) 삽입
    out_fm: list[str] = []
    inserted = False
    for line in kept:
        out_fm.append(line)
        if line.startswith("date:") and not inserted:
            out_fm.append(f"cluster: {cluster}")
            inserted = True
    if not inserted:
        out_fm.append(f"cluster: {cluster}")

    new_body = body
    if sources:  # 이번에 옮길 sources 가 있을 때만 본문 손댐(멱등)
        idx = new_body.find(f"\n{_SOURCES_HEADING}\n")
        if idx != -1:
            new_body = new_body[:idx]
        source_block = "\n".join(f"- {s}" for s in sources)
        new_body = f"{new_body.rstrip(chr(10))}\n\n{_SOURCES_HEADING}\n{source_block}\n"

    return "---\n" + "\n".join(out_fm) + "\n---\n" + new_body


def apply_clusters(vault_path: str | Path) -> dict[str, int]:
    """VAULT_INDEX 의 클러스터를 20-notes 전체 노트에 반영한다(노트 파일 수정).

    Returns:
        {"updated": 수정한 노트 수, "missing": 인덱스에 없던 노트 수}.
    """
    vault = Path(vault_path).expanduser()
    index_path = vault / INDEX_SUBDIR / VAULT_INDEX_FILENAME
    cluster_of = parse_index_clusters(index_path.read_text(encoding="utf-8"))

    topics_dir = vault / TOPICS_SUBDIR
    updated = 0
    missing = 0
    for path in sorted(topics_dir.glob("*.md")):
        cluster = cluster_of.get(path.stem)
        if cluster is None:
            missing += 1
            logger.warning("인덱스에 없는 주제, 미분류로 처리: %s", path.stem)
            cluster = FALLBACK_CLUSTER
        content = path.read_text(encoding="utf-8")
        new_content = transform_note(content, cluster)
        if new_content != content:
            _write_lf(path, new_content)
            updated += 1
    return {"updated": updated, "missing": missing}


def main() -> None:
    """CLI 진입점: 실 설정으로 클러스터를 노트에 적용한다."""
    from airvoice.config import load_settings

    logging.basicConfig(level=logging.INFO)
    settings = load_settings()
    stats = apply_clusters(settings.obsidian_vault)
    print(f"✅ 적용: {stats['updated']}개 노트 수정, 인덱스 누락 {stats['missing']}개")


if __name__ == "__main__":
    main()
