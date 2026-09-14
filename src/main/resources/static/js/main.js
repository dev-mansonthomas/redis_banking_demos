/**
 * Redis Banking Workshop — Shared JS
 * Dark mode toggle + utility functions
 */
(function () {
    'use strict';

    // --- Dark Mode Toggle ---
    const THEME_KEY = 'redis-workshop-theme';
    const PRESENTER_MODE_KEY = 'redis-workshop-presenter-mode';

    function getPreferredTheme() {
        const stored = localStorage.getItem(THEME_KEY);
        if (stored) return stored;
        return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }

    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(THEME_KEY, theme);
    }

    function hasPresenterToggle() {
        return !!document.querySelector('.btn-presenter-fullscreen');
    }

    function isPresenterModeEnabled() {
        return !!(document.body && document.body.classList.contains('presenter-fullscreen'));
    }

    function readPresenterModePreference() {
        try {
            return sessionStorage.getItem(PRESENTER_MODE_KEY) === 'true';
        } catch (err) {
            return false;
        }
    }

    function writePresenterModePreference(enabled) {
        try {
            if (enabled) {
                sessionStorage.setItem(PRESENTER_MODE_KEY, 'true');
            } else {
                sessionStorage.removeItem(PRESENTER_MODE_KEY);
            }
        } catch (err) {
        }
    }

    function syncPresenterModeButtons() {
        var active = isPresenterModeEnabled();
        var label = active ? 'Exit fullscreen presenter mode' : 'Enter fullscreen presenter mode';

        document.querySelectorAll('.btn-presenter-fullscreen').forEach(function (button) {
            button.textContent = active ? '⤢ Exit Fullscreen' : '⛶ Fullscreen';
            button.setAttribute('aria-label', label);
            button.setAttribute('aria-pressed', active ? 'true' : 'false');
            button.title = label;
        });
    }

    function notifyPresenterLayoutChange(active) {
        // Widgets that cache their container size (Leaflet maps, charts) only recompute
        // on a resize event, and entering presenter mode never fires one.
        window.dispatchEvent(new CustomEvent('presenter-mode-change', { detail: { active: active } }));
        window.dispatchEvent(new Event('resize'));
    }

    function setPresenterMode(enabled, persist) {
        var active = !!enabled && hasPresenterToggle();
        if (document.body) {
            document.body.classList.toggle('presenter-fullscreen', active);
        }
        syncPresenterModeButtons();

        if (typeof window.requestAnimationFrame === 'function') {
            window.requestAnimationFrame(function () { notifyPresenterLayoutChange(active); });
        } else {
            notifyPresenterLayoutChange(active);
        }

        if (persist !== false) {
            writePresenterModePreference(active);
        }
    }

    function initPresenterMode() {
        if (!hasPresenterToggle()) {
            if (document.body) document.body.classList.remove('presenter-fullscreen');
            return;
        }

        if (readPresenterModePreference()) {
            setPresenterMode(true, false);
            return;
        }

        setPresenterMode(false, false);
    }

    window.togglePresenterMode = function (force) {
        var shouldEnable = typeof force === 'boolean' ? force : !isPresenterModeEnabled();
        setPresenterMode(shouldEnable);
        return isPresenterModeEnabled();
    };

    // Apply stored/preferred theme immediately
    applyTheme(getPreferredTheme());

    function normalizeToastType(type) {
        return ['success', 'error', 'warning', 'info'].indexOf(type) !== -1 ? type : 'info';
    }

    function extractApiMessage(body, fallbackStatusText, status) {
        if (body && typeof body === 'object') {
            return body.message || body.error || body.detail || body.details
                || ('Request failed with HTTP ' + status + '.');
        }
        if (typeof body === 'string' && body.trim()) return body.trim();
        if (fallbackStatusText) return fallbackStatusText;
        return 'Request failed with HTTP ' + status + '.';
    }

    function parseJsonResponse(res) {
        return res.text().then(function (text) {
            var body = {};
            if (text) {
                try {
                    body = JSON.parse(text);
                } catch (err) {
                    body = text;
                }
            }

            if (!res.ok) {
                throw Object.assign(new Error(extractApiMessage(body, res.statusText, res.status)), {
                    status: res.status,
                    body: body
                });
            }

            return body;
        });
    }

    window.parseJsonResponse = parseJsonResponse;

    function dismissToast(toast) {
        if (!toast || toast.dataset.closing === 'true') return;
        toast.dataset.closing = 'true';
        if (toast._toastTimer) {
            clearTimeout(toast._toastTimer);
            toast._toastTimer = null;
        }
        toast.classList.remove('is-visible');
        toast.classList.add('is-hiding');
        setTimeout(function () {
            if (toast.parentNode) toast.parentNode.removeChild(toast);
        }, 220);
    }

    window.showToast = function (message, type, duration) {
        var container = document.getElementById('toastContainer');
        if (!container || !message) return;

        var normalizedType = normalizeToastType(type);
        var toast = document.createElement('div');
        toast.className = 'toast toast-' + normalizedType;
        toast.setAttribute('role', normalizedType === 'error' ? 'alert' : 'status');

        var body = document.createElement('div');
        body.className = 'toast-body';

        var title = document.createElement('div');
        title.className = 'toast-title';
        title.textContent = normalizedType;

        var text = document.createElement('div');
        text.className = 'toast-message';
        text.textContent = String(message);

        var closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'toast-close';
        closeBtn.setAttribute('aria-label', 'Dismiss notification');
        closeBtn.textContent = '×';
        closeBtn.addEventListener('click', function () { dismissToast(toast); });

        body.appendChild(title);
        body.appendChild(text);
        toast.appendChild(body);
        toast.appendChild(closeBtn);
        container.appendChild(toast);

        requestAnimationFrame(function () {
            toast.classList.add('is-visible');
        });

        var timeoutMs = typeof duration === 'number'
            ? duration
            : (normalizedType === 'error' ? 5500 : 4000);
        if (timeoutMs > 0) {
            toast._toastTimer = setTimeout(function () { dismissToast(toast); }, timeoutMs);
        }
    };

    document.addEventListener('DOMContentLoaded', function () {
        const toggle = document.getElementById('themeToggle');
        if (toggle) {
            toggle.addEventListener('click', function () {
                const current = document.documentElement.getAttribute('data-theme');
                applyTheme(current === 'dark' ? 'light' : 'dark');
            });
        }

        // Set active option in nav select based on current path
        var navSelect = document.getElementById('useCaseSelect');
        if (navSelect) {
            var path = window.location.pathname;
            var matched = false;
            for (var i = 0; i < navSelect.options.length; i++) {
                if (navSelect.options[i].value === path) {
                    navSelect.selectedIndex = i;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                navSelect.selectedIndex = 0;
            }
        }

        initPresenterMode();
        initResetAll();
    });

    document.addEventListener('keydown', function (event) {
        if (event.defaultPrevented) return;
        if (event.key === 'Escape' && isPresenterModeEnabled()) {
            event.preventDefault();
            window.togglePresenterMode(false);
        }
    });

    // --- Reset All (navbar) ---
    function initResetAll() {
        var btn = document.getElementById('resetAllBtn');
        var modal = document.getElementById('resetAllModal');
        if (!btn || !modal) return;
        var label = btn.querySelector('.reset-label');
        var originalLabel = label ? label.textContent : 'Reset All Data';
        var cancelBtn = modal.querySelector('[data-reset-modal-cancel]');
        var confirmBtn = modal.querySelector('[data-reset-modal-confirm]');
        var endpoint = btn.getAttribute('data-reset-all-url') || '/api/reset-all';
        var isOpen = false;
        var isSubmitting = false;
        var lastFocused = null;

        function setButtonState(loading) {
            btn.disabled = loading;
            btn.classList.toggle('is-loading', loading);
            if (label) label.textContent = loading ? 'Resetting…' : originalLabel;
        }

        function closeModal(options) {
            var config = options || {};
            if (!isOpen) return;

            modal.classList.remove('is-visible');
            modal.setAttribute('aria-hidden', 'true');
            document.body.classList.remove('modal-open');
            isOpen = false;

            window.setTimeout(function () {
                if (!isOpen) modal.hidden = true;
            }, 180);

            if (config.restoreFocus !== false && lastFocused && typeof lastFocused.focus === 'function') {
                lastFocused.focus();
            }
        }

        function openModal() {
            if (isSubmitting || isOpen) return;

            lastFocused = document.activeElement;
            modal.hidden = false;
            modal.setAttribute('aria-hidden', 'false');
            document.body.classList.add('modal-open');
            isOpen = true;

            window.requestAnimationFrame(function () {
                modal.classList.add('is-visible');
                if (confirmBtn) confirmBtn.focus();
            });
        }

        btn.addEventListener('click', function () {
            if (btn.disabled) return;
            openModal();
        });

        if (cancelBtn) {
            cancelBtn.addEventListener('click', function () {
                closeModal();
            });
        }

        modal.addEventListener('click', function (event) {
            if (event.target === modal) closeModal();
        });

        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape' && isOpen) {
                event.preventDefault();
                closeModal();
            }
        });

        if (confirmBtn) {
            confirmBtn.addEventListener('click', function () {
                var shouldReload = false;
                if (isSubmitting) return;

                isSubmitting = true;
                closeModal({ restoreFocus: false });
                setButtonState(true);

                fetch(endpoint, {
                    method: 'POST',
                    headers: { 'Accept': 'application/json' }
                })
                    .then(parseJsonResponse)
                    .then(function (body) {
                        var totalMs = (body && body.totalMs) || 0;
                        shouldReload = true;
                        window.showToast('All demo data reset in ' + totalMs + 'ms.', 'success');
                        window.setTimeout(function () {
                            window.location.reload();
                        }, 900);
                    })
                    .catch(function (err) {
                        window.showToast((err && err.message) || 'Could not reset all demo data.', 'error', 6000);
                    })
                    .finally(function () {
                        isSubmitting = false;
                        if (!shouldReload) {
                            setButtonState(false);
                            btn.focus();
                        }
                    });
            });
        }
    }


    // --- Redis latency badge ---
    function latencyTone(latencyMs) {
        if (latencyMs < 1) return 'latency-green';
        if (latencyMs < 10) return 'latency-yellow';
        if (latencyMs < 100) return 'latency-orange';
        return 'latency-red';
    }

    function formatLatency(latencyMs) {
        if (latencyMs < 1) return latencyMs.toFixed(2) + 'ms';
        if (latencyMs < 10) return latencyMs.toFixed(1) + 'ms';
        return Math.round(latencyMs) + 'ms';
    }

    function findLatencyHeader(panel) {
        if (!panel) return null;
        var panelHeader = panel.querySelector('.demo-panel-header');
        if (panelHeader) return panelHeader;
        for (var i = 0; i < panel.children.length; i++) {
            var child = panel.children[i];
            if (/^H[1-4]$/.test(child.tagName)) return child;
        }
        return panel.querySelector('h1, h2, h3, h4');
    }

    window.extractLatency = function (response) {
        if (!response || !response.headers || !response.headers.get) return null;
        var raw = response.headers.get('X-Redis-Latency-Ms');
        if (raw == null || raw === '') return null;
        var parsed = Number(raw);
        return isFinite(parsed) && parsed >= 0 ? parsed : null;
    };

    window.showLatencyBadge = function (containerId, latencyMs) {
        if (latencyMs === null || latencyMs === undefined) return;

        var parsed = Number(latencyMs);
        if (!isFinite(parsed) || parsed < 0) return;

        var anchor = containerId ? document.getElementById(containerId) : null;
        var panel = anchor && anchor.closest ? anchor.closest('.demo-panel') : null;
        if (!panel) panel = document.querySelector('.usecase-panels .demo-panel, .demo-panel');
        if (!panel) return;

        var header = findLatencyHeader(panel);
        if (!header) return;

        var badge = header.querySelector('.redis-latency-badge');
        var presenterButton = header.querySelector('.btn-presenter-fullscreen');
        if (!badge) {
            badge = document.createElement('span');
            badge.className = 'redis-latency-badge';
            badge.title = 'Server-side Redis latency from X-Redis-Latency-Ms';
        }

        if (presenterButton && presenterButton.parentNode === header) {
            header.insertBefore(badge, presenterButton);
        } else if (badge.parentNode !== header) {
            header.appendChild(badge);
        }

        badge.className = 'redis-latency-badge ' + latencyTone(parsed);
        badge.textContent = '⚡ ' + formatLatency(parsed);
    };

    // --- Utility: POST JSON ---
    window.workshopFetch = function (url, data, containerId) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: data ? JSON.stringify(data) : undefined
        }).then(function (response) {
            window.showLatencyBadge(containerId, window.extractLatency(response));
            return parseJsonResponse(response);
        });
    };

    // --- Utility: GET JSON ---
    window.workshopGet = function (url, containerId) {
        return fetch(url).then(function (response) {
            window.showLatencyBadge(containerId, window.extractLatency(response));
            return parseJsonResponse(response);
        });
    };

    // --- Utility: Format JSON for display ---
    window.formatJson = function (obj) {
        return JSON.stringify(obj, null, 2);
    };

    // --- Code Tabs: shared initializer ---
    window.initCodeTabs = function () {
        document.querySelectorAll('.code-tab').forEach(function (tab) {
            tab.addEventListener('click', function () {
                document.querySelectorAll('.code-tab').forEach(function (t) { t.classList.remove('active'); });
                document.querySelectorAll('.code-block, .code-tab-content').forEach(function (c) { c.classList.remove('active'); });
                tab.classList.add('active');
                var target = document.getElementById('tab-' + tab.getAttribute('data-tab'));
                if (target) target.classList.add('active');
                if (window.Prism) Prism.highlightAll();
            });
        });
    };

    // --- Shared: HTML escape ---
    function escapeHtml(s) {
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function normalizeAnimationClasses(classes, fallback) {
        var value = classes == null ? fallback : classes;
        if (Array.isArray(value)) return value.filter(Boolean);
        return String(value || '')
            .split(/\s+/)
            .filter(Boolean);
    }

    function prefersReducedMotion() {
        return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    }

    window.animateResult = function (el, classes, delayMs) {
        if (!el || !el.classList) return el;

        var animationClasses = normalizeAnimationClasses(classes, ['fade-in']);
        animationClasses.forEach(function (className) {
            el.classList.remove(className);
        });

        if (typeof delayMs === 'number' && isFinite(delayMs) && delayMs > 0) {
            el.style.animationDelay = delayMs + 'ms';
        } else {
            el.style.removeProperty('animation-delay');
        }

        if (prefersReducedMotion()) return el;

        void el.offsetWidth;
        animationClasses.forEach(function (className) {
            el.classList.add(className);
        });
        return el;
    };

    window.animateChildren = function (root, selector, classes, staggerMs) {
        if (!root || !selector) return [];
        var nodes = Array.prototype.slice.call(root.querySelectorAll(selector));
        var step = typeof staggerMs === 'number' && isFinite(staggerMs) ? staggerMs : 0;
        nodes.forEach(function (node, index) {
            window.animateResult(node, classes || 'slide-up', step > 0 ? index * step : null);
        });
        return nodes;
    };

    window.renderAnimatedHtml = function (target, html, options) {
        if (!target) return null;
        var config = options || {};
        target.innerHTML = html || '';
        if (config.containerClasses !== false) {
            window.animateResult(target, config.containerClasses || 'fade-in');
        }
        if (config.childSelector) {
            window.animateChildren(target, config.childSelector, config.childClasses || 'slide-up', config.staggerMs || 0);
        }
        return target;
    };

    window.appendAnimatedElement = function (parent, child, classes, prepend) {
        if (!parent || !child) return child;
        if (prepend && parent.firstChild) {
            parent.insertBefore(child, parent.firstChild);
        } else if (prepend) {
            parent.insertBefore(child, null);
        } else {
            parent.appendChild(child);
        }
        window.animateResult(child, classes || 'slide-up');
        return child;
    };

    // --- Redis Commands Panel (SSE) ---
    // Each UC page sets window.WORKSHOP_UC = 'UCn'. We open an SSE stream
    // filtered by UC and push entries into #commands-output inside the
    // #redis-commands-card block. A one-shot REST call backfills any
    // commands that happened before the page loaded.
    var MAX_COMMANDS = 25;
    var _eventSource = null;

    function initRedisCommands() {
        var uc = window.WORKSHOP_UC;
        if (!uc) return;

        var card = document.getElementById('redis-commands-card');
        var output = document.getElementById('commands-output');
        var counter = document.getElementById('commands-counter');
        var copyAllBtn = document.getElementById('copy-all-commands-btn');
        var clearBtn = document.getElementById('clear-commands-btn');
        if (!card || !output) return;

        ensureCommandsPlaceholder(output);
        updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);

        if (copyAllBtn) {
            copyAllBtn.addEventListener('click', function () {
                var commands = getVisibleCommands(output);
                if (!commands.length) return;
                copyRedisCommand(copyAllBtn, commands.join('\n'), 'Copy all commands');
            });
        }

        if (clearBtn) {
            clearBtn.addEventListener('click', function () {
                clearCommandEntries(output);
                ensureCommandsPlaceholder(output);
                updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);
                card.style.display = '';
            });
        }

        // Backfill recent commands captured before page load
        fetch('/api/redis/commands?uc=' + encodeURIComponent(uc) + '&limit=10',
                { headers: { 'Accept': 'application/json' } })
            .then(function (res) { return res.ok ? res.json() : null; })
            .then(function (data) {
                if (!data) return;
                var commands = Array.isArray(data) ? data : (data.commands || []);
                if (commands.length === 0) return;

                card.style.display = '';
                removeCommandsPlaceholder(output);

                // Backend returns newest-first; insert in reverse so newest ends on top
                for (var i = commands.length - 1; i >= 0; i--) {
                    var el = createCommandEntry(commands[i]);
                    window.appendAnimatedElement(output, el, 'slide-up highlight-new', true);
                }
                trimCommandEntries(output);
                updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);
            })
            .catch(function () { /* silent */ });

        // Open SSE stream for real-time push
        var streamUrl = '/api/redis/commands/stream?uc=' + encodeURIComponent(uc);
        _eventSource = new EventSource(streamUrl);

        _eventSource.addEventListener('command', function (event) {
            var cmd;
            try { cmd = JSON.parse(event.data); } catch (e) { return; }

            card.style.display = '';
            removeCommandsPlaceholder(output);

            var el = createCommandEntry(cmd);
            window.appendAnimatedElement(output, el, 'slide-up highlight-new', true);

            trimCommandEntries(output);
            updateCommandsToolbar(output, counter, copyAllBtn, clearBtn);

            flashCodeShowcase(cmd.command);
        });

        _eventSource.onerror = function () {
            // EventSource auto-reconnects by default
        };
    }

    window.addEventListener('beforeunload', function () {
        if (_eventSource) {
            _eventSource.close();
            _eventSource = null;
        }
    });

    function createCommandEntry(cmd) {
        var fullCmd = cmd.fullCommand || ((cmd.command || '') + (cmd.key ? ' ' + cmd.key : ''));
        var commandName = cmd.command || '';
        var keyText = cmd.key || '';

        var details = document.createElement('details');
        details.className = 'redis-cmd-entry command-entry';
        details.setAttribute('data-full-command', fullCmd);

        var summary = document.createElement('summary');
        var summaryCode = document.createElement('code');
        summaryCode.className = 'cmd-summary';
        summaryCode.innerHTML = escapeHtml(commandName)
            + (keyText ? ' <span class="cmd-key-text">' + escapeHtml(keyText) + '</span>' : '');

        var copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.className = 'cmd-copy-btn';
        copyBtn.title = 'Copy command to clipboard';
        copyBtn.setAttribute('aria-label', 'Copy command to clipboard');
        copyBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none"'
            + ' stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
            + '<rect x="9" y="9" width="13" height="13" rx="2"/>'
            + '<path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>'
            + '</svg>';
        copyBtn.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            copyRedisCommand(copyBtn, fullCmd);
        });

        summary.appendChild(summaryCode);
        summary.appendChild(copyBtn);

        var expanded = document.createElement('div');
        expanded.className = 'cmd-expanded';
        expanded.innerHTML = '<div class="cmd-full"><span class="cmd-label">Full command</span>'
            + '<code>' + escapeHtml(fullCmd) + '</code></div>';

        details.appendChild(summary);
        details.appendChild(expanded);
        return details;
    }

    function getCommandEntries(output) {
        return output.querySelectorAll('.redis-cmd-entry.command-entry, .command-entry-simple');
    }

    function getVisibleCommands(output) {
        var entries = getCommandEntries(output);
        var commands = [];
        for (var i = 0; i < entries.length; i++) {
            var fullCommand = entries[i].getAttribute('data-full-command');
            if (fullCommand) commands.push(fullCommand);
        }
        return commands;
    }

    function removeCommandsPlaceholder(output) {
        var placeholder = output.querySelector('.command-log-empty, .commands-empty');
        if (placeholder) placeholder.remove();
    }

    function ensureCommandsPlaceholder(output) {
        if (getCommandEntries(output).length > 0) {
            removeCommandsPlaceholder(output);
            return;
        }

        if (output.querySelector('.command-log-empty, .commands-empty')) return;

        var placeholder = document.createElement('div');
        placeholder.className = 'commands-empty';
        placeholder.textContent = 'No Redis commands captured yet.';
        output.appendChild(placeholder);
    }

    function clearCommandEntries(output) {
        var entries = getCommandEntries(output);
        for (var i = 0; i < entries.length; i++) {
            entries[i].remove();
        }
    }

    function trimCommandEntries(output) {
        var entries = getCommandEntries(output);
        while (entries.length > MAX_COMMANDS) {
            entries[entries.length - 1].remove();
            entries = getCommandEntries(output);
        }
    }

    function updateCommandsToolbar(output, counter, copyAllBtn, clearBtn) {
        var count = getCommandEntries(output).length;
        if (counter) {
            counter.textContent = count + ' command' + (count === 1 ? '' : 's');
        }
        if (copyAllBtn) copyAllBtn.disabled = count === 0;
        if (clearBtn) clearBtn.disabled = count === 0;
    }

    // Briefly flash the code-showcase snippet that contains the executed command.
    // Matches the command token within the currently visible (active) code block,
    // ignoring substrings of longer commands (e.g. HGET inside HGETALL).
    function flashCodeShowcase(commandName) {
        if (!commandName) return;
        var panel = document.querySelector('.code-panel');
        if (!panel) return;

        var blocks = panel.querySelectorAll('.code-block.active, .code-tab-content.active');
        if (!blocks.length) blocks = panel.querySelectorAll('.code-block, .code-tab-content');

        var escaped = commandName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        var re = new RegExp('(^|[^A-Z0-9.])' + escaped + '(?![A-Z0-9.])');

        for (var i = 0; i < blocks.length; i++) {
            var pres = blocks[i].querySelectorAll('pre');
            for (var j = 0; j < pres.length; j++) {
                var code = pres[j].querySelector('code');
                var text = code ? code.textContent : pres[j].textContent;
                if (re.test(text)) {
                    var target = pres[j];
                    target.classList.remove('code-highlight-flash');
                    // Force reflow so animation restarts on repeated matches
                    void target.offsetWidth;
                    target.classList.add('code-highlight-flash');
                    setTimeout(function (el) {
                        return function () { el.classList.remove('code-highlight-flash'); };
                    }(target), 1700);
                    return;
                }
            }
        }
    }

    function fallbackCopyText(command) {
        try {
            var ta = document.createElement('textarea');
            ta.value = command;
            ta.setAttribute('readonly', '');
            ta.style.position = 'absolute';
            ta.style.left = '-9999px';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
            return true;
        } catch (e) {
            return false;
        }
    }

    function copyRedisCommand(btn, command, defaultTitle) {
        var baseTitle = defaultTitle || 'Copy command to clipboard';

        function flash() {
            btn.classList.add('cmd-copied');
            btn.title = 'Copied!';
            btn.setAttribute('aria-label', 'Copied!');
            setTimeout(function () {
                btn.classList.remove('cmd-copied');
                btn.title = baseTitle;
                btn.setAttribute('aria-label', baseTitle);
            }, 1500);
        }

        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(command).then(flash).catch(function () {
                if (fallbackCopyText(command)) flash();
            });
            return;
        }

        if (fallbackCopyText(command)) flash();
    }

    document.addEventListener('DOMContentLoaded', function () {
        if (!window.WORKSHOP_UC) return;
        initRedisCommands();
        initCodeTabs();
    });
})();
