(() => {
  const tokenStorage = window.AirVoiceTokenStorage;
  const state = {
    token: tokenStorage.read(),
    timer: null,
    audioUrl: null,
  };

  const $ = (id) => document.getElementById(id);
  const connectionPill = $('connectionPill');
  const connectionText = $('connectionText');
  const refreshButton = $('refreshButton');
  const authCard = $('authCard');
  const tokenForm = $('tokenForm');
  const tokenInput = $('tokenInput');
  const errorBanner = $('errorBanner');
  const errorText = $('errorText');
  const queueTable = $('queueTable');
  const notesList = $('notesList');
  const audioPlayer = $('audioPlayer');
  const stopAudioButton = $('stopAudioButton');

  const escapeHtml = (value) => String(value).replace(/[&<>'"]/g, (char) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
  }[char]));

  const formatBytes = (bytes) => {
    if (!Number.isFinite(bytes)) return '—';
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let value = bytes / 1024;
    let unit = units[0];
    for (let index = 1; value >= 1024 && index < units.length; index += 1) {
      value /= 1024;
      unit = units[index];
    }
    return `${value.toFixed(value >= 10 ? 0 : 1)} ${unit}`;
  };

  const formatDate = (iso, withTime = true) => {
    if (!iso) return '—';
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return '—';
    return new Intl.DateTimeFormat('ko-KR', withTime ? {
      month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
    } : { year: 'numeric', month: 'short', day: 'numeric' }).format(date);
  };

  const setConnection = (kind, message) => {
    connectionPill.classList.remove('is-online', 'is-error');
    if (kind) connectionPill.classList.add(`is-${kind}`);
    connectionText.textContent = message;
  };

  const showError = (message) => {
    errorText.textContent = message;
    errorBanner.hidden = false;
  };

  const clearError = () => { errorBanner.hidden = true; };

  const setAction = (stateLabel, message) => {
    $('actionState').textContent = stateLabel;
    $('actionText').textContent = message;
  };

  const authorizedFetch = (path) => fetch(path, {
    headers: { Authorization: `Bearer ${state.token}` },
    cache: 'no-store'
  });

  const setNoteViewer = (title, folder, description, content) => {
    $('noteViewerTitle').textContent = title;
    $('noteViewerFolder').textContent = folder;
    $('noteViewerDescription').textContent = description;
    $('noteViewerContent').textContent = content;
  };

  const clearAudio = () => {
    audioPlayer.pause();
    audioPlayer.removeAttribute('src');
    audioPlayer.load();
    audioPlayer.hidden = true;
    stopAudioButton.disabled = true;
    if (state.audioUrl) {
      URL.revokeObjectURL(state.audioUrl);
      state.audioUrl = null;
    }
  };

  const setAudioState = (stateLabel, description) => {
    $('audioState').textContent = stateLabel;
    $('audioDescription').textContent = description;
  };

  const renderQueue = (queue) => {
    $('queueMetric').textContent = `${queue.count}건`;
    $('queueCaption').textContent = queue.count ? `${formatBytes(queue.bytes)} · 수신 보관 중` : '현재 수신 보관 파일 없음';
    $('queueBadge').textContent = `${queue.count} FILE${queue.count === 1 ? '' : 'S'}`;
    if (!queue.items.length) {
      queueTable.innerHTML = '<tr><td colspan="4" class="empty-cell">수신 보관 중인 오디오가 없습니다.</td></tr>';
      return;
    }
    queueTable.innerHTML = queue.items.map((item) => `<tr>
      <td title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</td>
      <td>${formatBytes(item.size)}</td>
      <td>${formatDate(item.updatedAt)}</td>
      <td>${item.extension === 'm4a'
        ? `<button class="row-button" type="button" data-audio-name="${escapeHtml(item.name)}">듣기</button>`
        : '<span class="row-muted">M4A 아님</span>'}</td>
    </tr>`).join('');
  };

  const renderNotes = (notes) => {
    $('notesMetric').textContent = `${notes.count}개`;
    $('notesBadge').textContent = `${notes.count} NOTE${notes.count === 1 ? '' : 'S'}`;
    if (!notes.items.length) {
      notesList.innerHTML = '<li class="empty-list">공개된 노트가 없습니다.</li>';
      return;
    }
    notesList.innerHTML = notes.items.map((item) => `<li>
      <div class="note-main"><span class="note-name" title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</span><span class="note-folder">${escapeHtml(item.folder)}</span></div>
      <div class="note-actions"><time class="note-time" datetime="${escapeHtml(item.updatedAt)}">${formatDate(item.updatedAt)}</time><button class="row-button" type="button" data-note-folder="${escapeHtml(item.folder)}" data-note-name="${escapeHtml(item.name)}">보기</button></div>
    </li>`).join('');
  };

  const viewNote = async (folder, name) => {
    if (!state.token) return;
    setNoteViewer(name, '불러오는 중', '선택한 Markdown 원문을 읽고 있습니다.', '내용을 불러오는 중입니다…');
    try {
      const response = await authorizedFetch(
        `/api/v1/dashboard/notes/${encodeURIComponent(folder)}/${encodeURIComponent(name)}`
      );
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const note = await response.json();
      setNoteViewer(note.name, note.folder, '이 내용은 대시보드에서 수정할 수 없는 Markdown 원문입니다.', note.content);
    } catch (error) {
      const reason = error instanceof Error ? error.message : '알 수 없는 오류';
      setNoteViewer('내용을 불러오지 못했습니다', '오류', '토큰·서버 연결·파일 상태를 확인한 뒤 다시 시도해 주세요.', `오류: ${reason}`);
    }
  };

  const playAudio = async (filename) => {
    if (!state.token) return;
    clearAudio();
    setAudioState('불러오는 중', `${filename}을(를) 안전하게 가져와 재생 준비 중입니다.`);
    try {
      const response = await authorizedFetch(`/api/v1/dashboard/audio/${encodeURIComponent(filename)}`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const audioBlob = await response.blob();
      if (!audioBlob.size) throw new Error('빈 오디오 파일입니다');
      state.audioUrl = URL.createObjectURL(audioBlob);
      audioPlayer.src = state.audioUrl;
      audioPlayer.hidden = false;
      stopAudioButton.disabled = false;
      try {
        await audioPlayer.play();
        setAudioState('재생 중', `${filename} · 이 브라우저 세션에서만 재생합니다.`);
      } catch (_playError) {
        setAudioState('재생 준비', `${filename}을(를) 불러왔습니다. 재생 바의 ▶ 버튼을 누르세요.`);
      }
    } catch (error) {
      clearAudio();
      const reason = error instanceof Error ? error.message : '알 수 없는 오류';
      setAudioState('재생 실패', `${filename}을(를) 재생하지 못했습니다. (${reason})`);
    }
  };

  const renderStorage = (storage) => {
    if (!storage.available) {
      $('diskMetric').textContent = '확인 불가';
      $('diskCaption').textContent = '디스크 정보를 읽지 못했습니다.';
      $('storagePercent').textContent = '—';
      $('storageBar').style.width = '0%';
      $('storageUsed').textContent = '사용량 —';
      $('storageFree').textContent = '여유 —';
      return;
    }
    $('diskMetric').textContent = formatBytes(storage.free);
    $('diskCaption').textContent = `${storage.usedPercent}% 사용 중`;
    $('storagePercent').textContent = `${storage.usedPercent}% USED`;
    $('storageBar').style.width = `${Math.min(storage.usedPercent, 100)}%`;
    $('storageUsed').textContent = `사용량 ${formatBytes(storage.used)}`;
    $('storageFree').textContent = `여유 ${formatBytes(storage.free)}`;
  };

  const renderRuntime = (data) => {
    const receiver = data.receiver;
    const user = data.user;
    $('receiverMetric').textContent = receiver.status === 'operational' ? '정상 운영' : receiver.status;
    $('receiverCaption').textContent = `${user.name} · 마지막 확인 ${formatDate(data.generatedAt)}`;
    $('runtimeUser').textContent = user.name;
    $('runtimePort').textContent = `:${receiver.port}`;
    $('runtimeStarted').textContent = formatDate(receiver.startedAt);
    $('runtimeAuto').textContent = receiver.autoProcess ? '활성' : '수동 실행';
    $('runtimeTls').textContent = receiver.tlsEnabled ? 'HTTPS / TLS' : 'HTTP · LAN 전용';
    $('lastChecked').textContent = `LAST CHECK ${formatDate(data.generatedAt)}`;
    $('footerMeta').textContent = `동일 서버 · 읽기 전용 대시보드 · 자동 갱신 ${data.refreshSeconds}초`;
    if (data.queue.count && receiver.autoProcess) {
      setAction(
        '수신 파일 보관 중',
        `서버 자동 처리가 활성화되어 있습니다. 현재 ${data.queue.count}개 원본은 보존 기간 동안 남으며, 처리 결과는 ‘최근 노트’에서 확인하세요.`
      );
    } else if (data.queue.count) {
      setAction(
        '처리 시작 필요',
        `서버가 ${data.queue.count}개의 녹음을 보관 중이지만 자동 처리가 꺼져 있습니다. 기존 파이프라인을 실행한 뒤 ‘최근 노트’ 생성 여부를 확인하세요.`
      );
    } else if (receiver.autoProcess) {
      setAction(
        '수신 대기',
        '수신 보관함이 비어 있습니다. 새로 녹음한 뒤 10초 안에 ‘수신 파일’에 표시되는지 확인하세요.'
      );
    } else {
      setAction(
        '수동 처리 모드',
        '수신 보관함이 비어 있습니다. 다음 녹음은 수동 처리 대상입니다. 녹음 후 기존 처리 시작 절차를 실행하세요.'
      );
    }
  };

  const render = (data) => {
    renderRuntime(data);
    renderQueue(data.queue);
    renderNotes(data.notes);
    renderStorage(data.storage);
    $('endpointValue').textContent = window.location.origin;
  };

  const requestSummary = async () => {
    if (!state.token) {
      setConnection('', '연결 대기');
      setAction('연결 필요', '수신기 토큰을 입력하면 현재 상태와 확인할 항목을 안내합니다.');
      return;
    }
    refreshButton.disabled = true;
    setConnection('', '확인 중');
    try {
      const response = await authorizedFetch('/api/v1/dashboard/summary');
      if (response.status === 401) {
        state.token = '';
        tokenStorage.clear();
        authCard.classList.remove('connected');
        showError('인증이 만료되었거나 토큰이 올바르지 않습니다. 앱 설정의 Receiver token을 다시 입력해 주세요.');
        setConnection('error', '인증 실패');
        setAction('인증 필요', '올바른 Receiver token을 입력한 뒤 서버 상태를 다시 확인하세요.');
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      clearError();
      authCard.classList.add('connected');
      setConnection('online', '정상 연결');
      render(data);
    } catch (error) {
      setConnection('error', '서버 확인 실패');
      const reason = error instanceof Error ? error.message : '알 수 없는 오류';
      showError(`수신기 상태를 확인하지 못했습니다. 서버 주소·Wi-Fi 연결·토큰을 확인한 뒤 새로고침하세요. (${reason})`);
      setAction('상태 확인 실패', '오류 안내를 확인한 뒤 서버 주소, Wi-Fi 연결, Receiver token을 차례로 점검하세요.');
    } finally {
      refreshButton.disabled = false;
    }
  };

  const scheduleRefresh = () => {
    if (state.timer) window.clearInterval(state.timer);
    state.timer = window.setInterval(requestSummary, 10000);
  };

  tokenForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const token = tokenInput.value.trim();
    if (!token) return;
    state.token = token;
    tokenStorage.write(token);
    requestSummary();
  });

  $('disconnectButton').addEventListener('click', () => {
    clearAudio();
    setAudioState('대기', '수신 파일의 M4A에서 듣기를 누르면 이 브라우저에서만 재생합니다.');
    state.token = '';
    tokenStorage.clear();
    tokenInput.value = '';
    authCard.classList.remove('connected');
    setConnection('', '연결 대기');
    clearError();
    setAction('연결 필요', '수신기 토큰을 입력하면 현재 상태와 확인할 항목을 안내합니다.');
  });

  refreshButton.addEventListener('click', requestSummary);
  $('dismissError').addEventListener('click', clearError);
  queueTable.addEventListener('click', (event) => {
    const button = event.target.closest('[data-audio-name]');
    if (button) playAudio(button.dataset.audioName);
  });
  notesList.addEventListener('click', (event) => {
    const button = event.target.closest('[data-note-folder][data-note-name]');
    if (button) viewNote(button.dataset.noteFolder, button.dataset.noteName);
  });
  stopAudioButton.addEventListener('click', () => {
    clearAudio();
    setAudioState('중지됨', '다른 M4A 파일을 선택하면 새로 재생할 수 있습니다.');
  });
  window.addEventListener('pagehide', clearAudio);
  $('endpointValue').textContent = window.location.origin;
  scheduleRefresh();
  if (state.token) {
    tokenInput.value = state.token;
    requestSummary();
  }
})();
