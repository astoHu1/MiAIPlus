'use strict';

const modes = {
  native: {
    chat: '小爱当前模型',
    phone: '小爱当前模型',
    label: '默认模式',
    title: '不用先买一份 API。',
    description: '控屏规划保持「小爱当前模型」，API 配置全部留空。先用已有的小爱，再决定要不要接入外部服务。',
    active: 'chat'
  },
  external: {
    chat: '你的外部模型',
    phone: '你的外部模型',
    label: '外部模型对话',
    title: '换模型，不换聊天习惯。',
    description: '填写自己的服务地址、密钥和模型，启用外部模型对话。保存多套服务配置，支持 Chat Completions 与 Responses 两种协议。',
    active: 'chat'
  },
  split: {
    chat: '小爱当前模型',
    phone: '独立外部视觉模型',
    label: '独立控屏规划',
    title: '聊天与控屏，各选所长。',
    description: '保留日常聊天模型，单独将手机操作交给支持图片与工具调用的外部模型。对话和控屏，不必绑在同一个选择上。',
    active: 'phone'
  }
};

const buttons = Array.from(document.querySelectorAll('[data-mode]'));
const chatDest = document.querySelector('#chat-dest');
const phoneDest = document.querySelector('#phone-dest');
const modeLabel = document.querySelector('#mode-label');
const modeTitle = document.querySelector('#mode-title');
const modeDescription = document.querySelector('#mode-description');

function setDestination(node, text, active) {
  node.firstChild.textContent = text;
  node.classList.toggle('is-active', active);
}

function selectMode(mode) {
  const next = modes[mode];
  if (!next) return;

  for (const button of buttons) {
    button.setAttribute('aria-pressed', String(button.dataset.mode === mode));
  }

  setDestination(chatDest, next.chat, next.active === 'chat');
  setDestination(phoneDest, next.phone, next.active === 'phone');
  modeLabel.textContent = next.label;
  modeTitle.textContent = next.title;
  modeDescription.textContent = next.description;
}

for (const button of buttons) {
  button.addEventListener('click', () => selectMode(button.dataset.mode));
}

selectMode('native');
