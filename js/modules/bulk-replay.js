// Bulk Replay Logic
import { state } from './state.js';
import { elements } from './ui.js';
import { generateAttackRequests } from './attack-engine.js';
import { formatBytes, highlightHTTP, renderDiff } from './utils.js';

export function setupBulkReplay() {
    const bulkReplayBtn = document.getElementById('bulk-replay-btn');
    const bulkConfigModal = document.getElementById('bulk-config-modal');
    const closeModalBtn = document.querySelector('.close-modal');
    const startAttackBtn = document.getElementById('start-attack-btn');
    const bulkReplayPane = document.getElementById('bulk-replay-pane');
    const bulkResultsTable = document.getElementById('bulk-results-table').querySelector('tbody');
    const bulkProgressBar = document.getElementById('bulk-progress-bar');
    const bulkProgressText = document.getElementById('bulk-progress-text');
    const bulkStopBtn = document.getElementById('bulk-stop-btn');
    const bulkCloseBtn = document.getElementById('bulk-close-btn');
    const verticalResizeHandle = document.querySelector('.vertical-resize-handle');

    // We use elements.rawRequestInput from ui.js

    // Helper to check for payload markers
    function checkPayloadMarkers() {
        if (!bulkReplayBtn || !elements.rawRequestInput) return;

        const content = elements.rawRequestInput.innerText;
        const hasMarkers = /§[\s\S]*?§/.test(content);

        if (hasMarkers) {
            bulkReplayBtn.disabled = false;
            bulkReplayBtn.classList.add('ready');
        } else {
            bulkReplayBtn.disabled = true;
            bulkReplayBtn.classList.remove('ready');
        }
    }

    // Initial check
    checkPayloadMarkers();

    // Listen for changes in input
    if (elements.rawRequestInput) {
        elements.rawRequestInput.addEventListener('input', checkPayloadMarkers);
        elements.rawRequestInput.addEventListener('keyup', checkPayloadMarkers);
        elements.rawRequestInput.addEventListener('click', checkPayloadMarkers);

        const observer = new MutationObserver(checkPayloadMarkers);
        observer.observe(elements.rawRequestInput, { childList: true, subtree: true, characterData: true });
    }

    // Bulk Replay Button
    if (bulkReplayBtn) {
        bulkReplayBtn.addEventListener('click', () => {
            if (bulkReplayBtn.disabled) return;

            const content = elements.rawRequestInput.innerText;
            const matches = content.match(/§[\s\S]*?§/g);
            const count = matches ? matches.length : 0;
            document.getElementById('payload-count').textContent = count;

            if (!matches || count === 0) {
                alert('No payload positions found. Mark parameters with § to enable Bulk Replay.');
                return;
            }

            // Initialize position configs
            state.positionConfigs = matches.map((match, index) => ({
                index,
                originalValue: match.replace(/§/g, ''),
                type: 'simple-list',
                list: '',
                numbers: { from: 1, to: 10, step: 1 }
            }));

            populatePositionsContainer(matches);

            state.currentAttackType = 'sniper';
            document.getElementById('attack-type').value = 'sniper';
            updateAttackTypeUI('sniper');

            bulkConfigModal.style.display = 'block';
        });
    }

    function populatePositionsContainer(matches) {
        const container = document.getElementById('positions-container');
        container.innerHTML = '';

        matches.forEach((match, index) => {
            const cleanValue = match.replace(/§/g, '');
            const card = document.createElement('div');
            card.className = 'position-card';
            card.dataset.index = index;
            card.innerHTML = `
                <div class="position-card-header">
                    <span class="position-title">Position ${index + 1}</span>
                    <span class="position-value">${cleanValue.substring(0, 30)}${cleanValue.length > 30 ? '...' : ''}</span>
                </div>
                <div class="form-group">
                    <label>Payload Type</label>
                    <select class="payload-type-select form-control" data-index="${index}">
                        <option value="simple-list">Simple List</option>
                        <option value="numbers">Numbers</option>
                    </select>
                </div>
                <div class="payload-options-simple-list">
                    <div class="form-group">
                        <label>Payloads (one per line)</label>
                        <textarea class="payload-list-input form-control" rows="5" data-index="${index}" placeholder="admin&#10;user&#10;guest"></textarea>
                    </div>
                </div>
                <div class="payload-options-numbers" style="display: none;">
                    <div class="form-row">
                        <div class="form-group">
                            <label>From</label>
                            <input type="number" class="num-from-input form-control" data-index="${index}" value="1">
                        </div>
                        <div class="form-group">
                            <label>To</label>
                            <input type="number" class="num-to-input form-control" data-index="${index}" value="10">
                        </div>
                        <div class="form-group">
                            <label>Step</label>
                            <input type="number" class="num-step-input form-control" data-index="${index}" value="1">
                        </div>
                    </div>
                </div>
            `;
            container.appendChild(card);

            const typeSelect = card.querySelector('.payload-type-select');
            typeSelect.addEventListener('change', (e) => {
                const card = e.target.closest('.position-card');
                const simpleList = card.querySelector('.payload-options-simple-list');
                const numbers = card.querySelector('.payload-options-numbers');
                if (e.target.value === 'simple-list') {
                    simpleList.style.display = 'block';
                    numbers.style.display = 'none';
                } else {
                    simpleList.style.display = 'none';
                    numbers.style.display = 'block';
                }
            });
        });
    }

    const attackTypeSelect = document.getElementById('attack-type');
    if (attackTypeSelect) {
        attackTypeSelect.addEventListener('change', (e) => {
            state.currentAttackType = e.target.value;
            updateAttackTypeUI(e.target.value);
        });
    }

    function updateAttackTypeUI(attackType) {
        const positionsContainer = document.getElementById('positions-container');
        const batteringRamConfig = document.getElementById('battering-ram-config');
        const helpText = document.getElementById('attack-type-help');

        const helpTexts = {
            'sniper': 'Sniper: Tests each position independently with its own payloads. Others remain unchanged.',
            'battering-ram': 'Battering Ram: All positions receive the same payload value from a shared list.',
            'pitchfork': 'Pitchfork: Zips payloads across positions (index-wise). Stops at shortest list.',
            'cluster-bomb': 'Cluster Bomb: Tests all combinations of payloads across positions (Cartesian product).'
        };
        helpText.textContent = helpTexts[attackType] || '';

        if (attackType === 'battering-ram') {
            positionsContainer.style.display = 'none';
            batteringRamConfig.style.display = 'block';
        } else {
            positionsContainer.style.display = 'block';
            batteringRamConfig.style.display = 'none';
        }
    }

    if (closeModalBtn) {
        closeModalBtn.addEventListener('click', () => {
            bulkConfigModal.style.display = 'none';
        });
    }

    window.addEventListener('click', (e) => {
        if (e.target === bulkConfigModal) {
            bulkConfigModal.style.display = 'none';
        }
    });

    const batteringRamTypeSelect = document.querySelector('#battering-ram-config .payload-type-select');
    if (batteringRamTypeSelect) {
        batteringRamTypeSelect.addEventListener('change', (e) => {
            const container = document.getElementById('battering-ram-config');
            const simpleList = container.querySelector('.payload-options-simple-list');
            const numbers = container.querySelector('.payload-options-numbers');
            if (e.target.value === 'simple-list') {
                simpleList.style.display = 'block';
                numbers.style.display = 'none';
            } else {
                simpleList.style.display = 'none';
                numbers.style.display = 'block';
            }
        });
    }

    if (startAttackBtn) {
        startAttackBtn.addEventListener('click', () => {
            startBulkReplay();
        });
    }

    if (bulkStopBtn) {
        bulkStopBtn.addEventListener('click', () => {
            if (typeof repAndroid !== 'undefined') {
                repAndroid.stopBulkReplay();
                bulkStopBtn.disabled = true;
                bulkStopBtn.title = 'Stopping...';
            } else {
                // Fallback for non-android env
                state.shouldStopBulk = true;
            }
        });
    }

    if (bulkCloseBtn) {
        bulkCloseBtn.addEventListener('click', () => {
            bulkReplayPane.style.display = 'none';
            verticalResizeHandle.style.display = 'none';
            state.shouldStopBulk = true;
        });
    }

    // Vertical Resize Handle
    let isVerticalResizing = false;
    if (verticalResizeHandle) {
        verticalResizeHandle.addEventListener('mousedown', (e) => {
            isVerticalResizing = true;
            document.body.style.cursor = 'row-resize';
        });

        document.addEventListener('mousemove', (e) => {
            if (!isVerticalResizing) return;
            const containerHeight = document.querySelector('.main-content').offsetHeight;
            const newHeight = containerHeight - e.clientY;
            if (newHeight > 100 && newHeight < containerHeight - 100) {
                bulkReplayPane.style.height = `${newHeight}px`;
            }
        });

        document.addEventListener('mouseup', () => {
            isVerticalResizing = false;
            document.body.style.cursor = 'default';
        });
    }

    // Context Menu: Mark Payload
    const contextMenu = document.getElementById('context-menu');
    const markPayloadItem = contextMenu.querySelector('[data-action="mark-payload"]');
    if (markPayloadItem) {
        markPayloadItem.addEventListener('click', () => {
            const selection = window.getSelection();
            if (!selection.rangeCount) return;

            const range = selection.getRangeAt(0);
            const selectedText = range.toString();

            if (selectedText) {
                document.execCommand('insertText', false, `§${selectedText}§`);
            } else {
                document.execCommand('insertText', false, '§§');
            }
            contextMenu.classList.remove('show');
        });
    }

    async function startBulkReplay() {
        const template = elements.rawRequestInput.innerText;

        // ... (Payload configuration logic remains the same)
        if (state.currentAttackType === 'battering-ram') {
            const container = document.getElementById('battering-ram-config');
            const type = container.querySelector('.payload-type-select').value;
            const sharedConfig = {
                type,
                list: type === 'simple-list' ? container.querySelector('.payload-list-input').value : '',
                numbers: type === 'numbers' ? {
                    from: parseInt(container.querySelector('.num-from-input').value),
                    to: parseInt(container.querySelector('.num-to-input').value),
                    step: parseInt(container.querySelector('.num-step-input').value)
                } : { from: 1, to: 10, step: 1 }
            };
            state.positionConfigs.forEach(config => Object.assign(config, sharedConfig));
        } else {
            document.querySelectorAll('.position-card').forEach((card, index) => {
                const type = card.querySelector('.payload-type-select').value;
                state.positionConfigs[index] = {
                    ...state.positionConfigs[index],
                    type,
                    list: type === 'simple-list' ? card.querySelector('.payload-list-input').value : '',
                    numbers: type === 'numbers' ? {
                        from: parseInt(card.querySelector('.num-from-input').value),
                        to: parseInt(card.querySelector('.num-to-input').value),
                        step: parseInt(card.querySelector('.num-step-input').value)
                    } : { from: 1, to: 10, step: 1 }
                };
            });
        }

        let attackRequests;
        try {
            attackRequests = generateAttackRequests(state.currentAttackType, state.positionConfigs, template);
        } catch (error) {
            alert(`Error generating attack requests: ${error.message}`);
            return;
        }

        if (attackRequests.length === 0) {
            alert('No requests generated. Please check your payload configuration.');
            return;
        }

        if (state.currentAttackType === 'cluster-bomb' && attackRequests.length > 1000) {
            if (!confirm(`This will generate ${attackRequests.length} requests. Continue?`)) {
                return;
            }
        }

        bulkConfigModal.style.display = 'none';

        // Setup UI
        const baselineResponse = elements.rawResponseDisplay.textContent || '';
        bulkReplayPane.style.display = 'flex';
        verticalResizeHandle.style.display = 'block';
        bulkResultsTable.innerHTML = '';
        if (bulkStopBtn) {
            bulkStopBtn.disabled = false;
            bulkStopBtn.title = 'Stop Attack';
        }

        const bulkResults = [];

        // Define the callback for the Android bridge
        window.onBulkReplayUpdate = (result) => {
            const { index, total, error, stopped, complete } = result;

            // Update progress bar
            const progress = (index + 1) / total * 100;
            bulkProgressBar.style.setProperty('--progress', `${progress}%`);
            bulkProgressText.textContent = `${index + 1}/${total}`;

            // Store result
            bulkResults[index] = result;

            // Add row if it doesn't exist
            let row = bulkResultsTable.querySelector(`tr[data-index="${index}"]`);
            if (!row) {
                 row = document.createElement('tr');
                 row.dataset.index = index;
                 row.innerHTML = `
                    <td>${index + 1}</td>
                    <td>${attackRequests[index].payloads.join(', ')}</td>
                    <td class="status-cell"></td>
                    <td class="size-cell"></td>
                    <td class="time-cell"></td>
                 `;
                bulkResultsTable.appendChild(row);
                row.scrollIntoView({ behavior: 'smooth', block: 'end' });

                 row.addEventListener('click', () => {
                     bulkResultsTable.querySelectorAll('tr').forEach(r => r.classList.remove('selected'));
                     row.classList.add('selected');
                     const result = bulkResults[index];
                     if (result) {
                         elements.rawRequestInput.innerText = result.requestContent;
                         elements.resStatus.textContent = `${result.status} ${result.statusText}`;
                         elements.resTime.textContent = `${result.duration}ms`;
                         elements.resSize.textContent = formatBytes(result.size);
                         elements.rawResponseDisplay.innerHTML = highlightHTTP(result.body);
                     }
                 });
            }

            // Update row with result
            const statusCell = row.querySelector('.status-cell');
            const sizeCell = row.querySelector('.size-cell');
            const timeCell = row.querySelector('.time-cell');

            if (error) {
                statusCell.textContent = 'Error';
                statusCell.title = error;
            } else {
                statusCell.textContent = `${result.status} ${result.statusText}`;
                sizeCell.textContent = formatBytes(result.size);
                timeCell.textContent = `${result.duration}ms`;
            }

            if (stopped || complete) {
                 bulkStopBtn.disabled = true;
                 bulkStopBtn.title = complete ? 'Finished' : 'Stopped';
                 delete window.onBulkReplayUpdate; // Clean up
            }
        };

        // Start the attack via the Android bridge
        if (typeof repAndroid !== 'undefined') {
            repAndroid.executeBulkReplay(JSON.stringify(attackRequests), 'window.onBulkReplayUpdate');
        } else {
            alert('Android bridge not available.');
        }
    }
}
