// v0.3 FR-53a · 目标向导 AI 推荐按钮 · 通用 JS
// 用法:onclick="adviseAi('retirement'|'education'|'emergency', formId, btn)"
(function() {
  function getCsrf() {
    const tokenMeta = document.querySelector('meta[name="_csrf"]');
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    return {
      token: tokenMeta ? tokenMeta.content : '',
      header: headerMeta ? headerMeta.content : 'X-XSRF-TOKEN'
    };
  }

  // 字段名映射 · backend JSON key → form element name(同名 · 直接复用)
  const FIELD_KEYS = [
    'name','currentAge','retireAge','monthlyExpense','inflationRate','withdrawalRate',
    'childMemberId','childBirthYear','targetYearOffset','targetAmount',
    'monthsTarget','autoBaseline','fixedBaseline'
  ];

  window.adviseAi = async function(type, formId, btn) {
    const orig = btn.textContent;
    btn.textContent = '🤖 思考中...';
    btn.disabled = true;
    try {
      const csrf = getCsrf();
      const resp = await fetch('/goals/advise/' + type, {
        method: 'POST',
        headers: { [csrf.header]: csrf.token }
      });
      const data = await resp.json();
      if (!data.ok) {
        alert('AI 暂不可用:' + (data.error || ''));
        return;
      }
      const form = document.getElementById(formId);
      FIELD_KEYS.forEach(k => {
        if (data[k] != null && form.elements[k]) {
          const el = form.elements[k];
          if (el.type === 'radio') {
            // autoBaseline 单选
            Array.from(form.elements[k]).forEach(r => r.checked = (String(r.value) === String(data[k])));
          } else {
            el.value = data[k];
          }
        }
      });
      if (data.rationale) {
        const r = document.getElementById('ai-rationale');
        if (r) {
          r.textContent = '✨ ' + data.rationale;
          r.classList.remove('hidden');
        }
      }
    } catch (e) {
      alert('请求失败:' + e.message);
    } finally {
      btn.textContent = orig;
      btn.disabled = false;
    }
  };
})();
