/**
 * Android Bridge - Provides Chrome extension API compatibility
 * This file bridges the existing Chrome extension code to Android native functions
 */

(function() {
    'use strict';

    // Check if Android interface is available
    if (typeof Android === 'undefined') {
        console.warn('Android interface not available');
        return;
    }

    // Polyfill chrome.storage API
    window.chrome = window.chrome || {};
    window.chrome.storage = {
        local: {
            get: function(keys, callback) {
                try {
                    const stored = localStorage.getItem('chrome_storage_local') || '{}';
                    const data = JSON.parse(stored);

                    if (typeof keys === 'string') {
                        callback({ [keys]: data[keys] });
                    } else if (Array.isArray(keys)) {
                        const result = {};
                        keys.forEach(key => result[key] = data[key]);
                        callback(result);
                    } else {
                        callback(data);
                    }
                } catch (e) {
                    console.error('Storage get error:', e);
                    callback({});
                }
            },
            set: function(items, callback) {
                try {
                    const stored = localStorage.getItem('chrome_storage_local') || '{}';
                    const data = JSON.parse(stored);
                    Object.assign(data, items);
                    localStorage.setItem('chrome_storage_local', JSON.stringify(data));
                    if (callback) callback();
                } catch (e) {
                    console.error('Storage set error:', e);
                }
            }
        }
    };

    // Polyfill Chrome DevTools APIs
    if (!window.chrome.devtools) {
        window.chrome.devtools = {};
    }

    // 1. DevTools network API polyfill
    if (!window.chrome.devtools.network) {
        window.chrome.devtools.network = {
            onRequestFinished: {
                _listeners: [],
                addListener: function(callback) {
                    this._listeners.push(callback);
                    // Store globally so Android can trigger it
                    window._networkListeners = this._listeners;
                },
                removeListener: function(callback) {
                    const idx = this._listeners.indexOf(callback);
                    if (idx > -1) this._listeners.splice(idx, 1);
                }
            }
        };
    }

    // 2. DevTools panels API polyfill
    if (!window.chrome.devtools.panels) {
        window.chrome.devtools.panels = {
            themeName: window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
        };
    }

    // 3. Function to trigger listeners when Android captures a request
    window.onAndroidRequestCaptured = function(requestData) {
        // Convert Android format to Chrome DevTools format
        const chromeRequest = {
            request: {
                method: requestData.method,
                url: requestData.url,
                headers: Object.entries(requestData.headers || {}).map(([name, value]) => ({name, value})),
                postData: { text: requestData.body || '' }
            },
            response: {
                status: requestData.response_status || 0,
                headers: Object.entries(requestData.response_headers || {}).map(([name, value]) => ({name, value})),
            },
            getContent: function(callback) {
                callback(requestData.response_body || '', 'text/plain');
            },
            time: requestData.timestamp
        };

        // Trigger all registered listeners
        if (window._networkListeners) {
            window._networkListeners.forEach(listener => {
                try {
                    listener(chromeRequest);
                } catch(e) {
                    console.error('Network listener error:', e);
                }
            });
        }
    };

    // Capture control functions
    window.repAndroid = {
        startCapture: function() {
            Android.startCapture();
        },

        stopCapture: function() {
            Android.stopCapture();
        },

        sendRequest: function(request) {
            return new Promise((resolve, reject) => {
                try {
                    const requestJson = JSON.stringify(request);
                    const responseJson = Android.sendRequest(requestJson);
                    const response = JSON.parse(responseJson);

                    if (response.error) {
                        reject(new Error(response.error));
                    } else {
                        resolve(response);
                    }
                } catch (e) {
                    reject(e);
                }
            });
        },

        saveRequest: function(request) {
            try {
                Android.saveRequest(JSON.stringify(request));
            } catch (e) {
                console.error('Save request error:', e);
            }
        },

        getRequests: function() {
            try {
                const json = Android.getRequests();
                return JSON.parse(json);
            } catch (e) {
                console.error('Get requests error:', e);
                return [];
            }
        },

        clearRequests: function() {
            Android.clearRequests();
        },

        deleteRequest: function(id) {
            try {
                return Android.deleteRequest(id);
            } catch (e) {
                console.error('Delete request error:', e);
                return false;
            }
        },

        updateRequest: function(id, request) {
            try {
                const requestJson = JSON.stringify(request);
                return Android.updateRequest(id, requestJson);
            } catch (e) {
                console.error('Update request error:', e);
                return false;
            }
        },

        searchRequests: function(query) {
            try {
                const json = Android.searchRequests(query);
                return JSON.parse(json);
            } catch (e) {
                console.error('Search requests error:', e);
                return [];
            }
        },

        getRequestById: function(id) {
            try {
                const json = Android.getRequestById(id);
                return JSON.parse(json);
            } catch (e) {
                console.error('Get request by ID error:', e);
                return null;
            }
        },

        toggleStar: function(id) {
            try {
                return Android.toggleStar(id);
            } catch (e) {
                console.error('Toggle star error:', e);
                return false;
            }
        },

        exportAllRequests: function() {
            try {
                const json = Android.exportAllRequests();
                return JSON.parse(json);
            } catch (e) {
                console.error('Export all requests error:', e);
                return [];
            }
        },

        showToast: function(message) {
            Android.showToast(message);
        },

        executeBulkReplay: function(requests, callback) {
            Android.executeBulkReplay(JSON.stringify(requests), callback);
        },

        stopBulkReplay: function() {
            Android.stopBulkReplay();
        },

        callAnthropicAPI: function(apiKey, model, systemPrompt, userMessage) {
            return new Promise((resolve, reject) => {
                const callbackName = '_anthropicCallback_' + Date.now();
                window[callbackName] = function(response, status) {
                    delete window[callbackName];
                    if (status === 200) {
                        try {
                            resolve(JSON.parse(response));
                        } catch (e) {
                            resolve({ content: [{ text: response }] });
                        }
                    } else {
                        reject(new Error(response));
                    }
                };
                Android.callAnthropicAPI(apiKey, model, systemPrompt, userMessage, callbackName);
            });
        }
    };

    // Listen for captured requests
    window.onCaptureStarted = function() {
        console.log('Capture started');
        // Trigger UI update
        if (typeof window.onCaptureStatusChanged === 'function') {
            window.onCaptureStatusChanged(true);
        }
    };

    // Override fetch to intercept and save requests
    const originalFetch = window.fetch;
    window.fetch = function(...args) {
        const request = args[0];
        const options = args[1] || {};

        // Log request
        console.log('Fetch intercepted:', request, options);

        return originalFetch.apply(this, args);
    };

    console.log('Android bridge initialized');

    // Notify app that bridge is ready
    if (typeof window.onAndroidBridgeReady === 'function') {
        window.onAndroidBridgeReady();
    }
})();
