
export async function instantiate(imports={}, runInitializer=true) {
    const cachedJsObjects = new WeakMap();
    // ref must be non-null
    function getCachedJsObject(ref, ifNotCached) {
        if (typeof ref !== 'object' && typeof ref !== 'function') return ifNotCached;
        const cached = cachedJsObjects.get(ref);
        if (cached !== void 0) return cached;
        cachedJsObjects.set(ref, ifNotCached);
        return ifNotCached;
    }

    const _ref_Li9za2lrby5tanM_ = imports['./skiko.mjs'];
    const _ref_Li9za2lrby5tanM_c2tpa29BcGk = imports['./skiko.mjs'].skikoApi;
    const _ref_QGpzLWpvZGEvY29yZQ_ = imports['@js-joda/core'];
    
    const js_code = {
        'kotlin.captureStackTrace' : () => new Error().stack,
        'kotlin.wasm.internal.stringLength' : (x) => x.length,
        'kotlin.wasm.internal.jsExportStringToWasm' : (src, srcOffset, srcLength, dstAddr) => { 
            const mem16 = new Uint16Array(wasmExports.memory.buffer, dstAddr, srcLength);
            let arrayIndex = 0;
            let srcIndex = srcOffset;
            while (arrayIndex < srcLength) {
                mem16.set([src.charCodeAt(srcIndex)], arrayIndex);
                srcIndex++;
                arrayIndex++;
            }     
             },
        'kotlin.wasm.internal.importStringFromWasm' : (address, length, prefix) => { 
            const mem16 = new Uint16Array(wasmExports.memory.buffer, address, length);
            const str = String.fromCharCode.apply(null, mem16);
            return (prefix == null) ? str : prefix + str;
             },
        'kotlin.wasm.internal.getJsEmptyString' : () => '',
        'kotlin.wasm.internal.externrefToInt' : (ref) => Number(ref),
        'kotlin.wasm.internal.externrefToDouble' : (ref) => Number(ref),
        'kotlin.wasm.internal.externrefToString' : (ref) => String(ref),
        'kotlin.wasm.internal.externrefEquals' : (lhs, rhs) => lhs === rhs,
        'kotlin.wasm.internal.externrefHashCode' : 
        (() => {
        const dataView = new DataView(new ArrayBuffer(8));
        function numberHashCode(obj) {
            if ((obj | 0) === obj) {
                return obj | 0;
            } else {
                dataView.setFloat64(0, obj, true);
                return (dataView.getInt32(0, true) * 31 | 0) + dataView.getInt32(4, true) | 0;
            }
        }
        
        const hashCodes = new WeakMap();
        function getObjectHashCode(obj) {
            const res = hashCodes.get(obj);
            if (res === undefined) {
                const POW_2_32 = 4294967296;
                const hash = (Math.random() * POW_2_32) | 0;
                hashCodes.set(obj, hash);
                return hash;
            }
            return res;
        }
        
        function getStringHashCode(str) {
            var hash = 0;
            for (var i = 0; i < str.length; i++) {
                var code  = str.charCodeAt(i);
                hash  = (hash * 31 + code) | 0;
            }
            return hash;
        }
        
        return (obj) => {
            if (obj == null) {
                return 0;
            }
            switch (typeof obj) {
                case "object":
                case "function":
                    return getObjectHashCode(obj);
                case "number":
                    return numberHashCode(obj);
                case "boolean":
                    return obj ? 1231 : 1237;
                default:
                    return getStringHashCode(String(obj)); 
            }
        }
        })(),
        'kotlin.wasm.internal.isNullish' : (ref) => ref == null,
        'kotlin.wasm.internal.intToExternref' : (x) => x,
        'kotlin.wasm.internal.getJsTrue' : () => true,
        'kotlin.wasm.internal.getJsFalse' : () => false,
        'kotlin.wasm.internal.newJsArray' : () => [],
        'kotlin.wasm.internal.jsArrayPush' : (array, element) => { array.push(element); },
        'kotlin.wasm.internal.getCachedJsObject_$external_fun' : (p0, p1) => getCachedJsObject(p0, p1),
        'kotlin.js.jsCatch' : (f) => { 
            let result = null;
            try { 
                f();
            } catch (e) {
               result = e;
            }
            return result;
             },
        'kotlin.js.__convertKotlinClosureToJsClosure_(()->Unit)' : (f) => getCachedJsObject(f, () => wasmExports['__callFunction_(()->Unit)'](f, )),
        'kotlin.js.jsThrow' : (e) => { throw e; },
        'kotlin.io.printError' : (error) => console.error(error),
        'kotlin.io.printlnImpl' : (message) => console.log(message),
        'kotlin.js.jsArrayGet' : (array, index) => array[index],
        'kotlin.js.jsArraySet' : (array, index, value) => { array[index] = value },
        'kotlin.js.JsArray_$external_fun' : () => new Array(),
        'kotlin.js.length_$external_prop_getter' : (_this) => _this.length,
        'kotlin.js.stackPlaceHolder_js_code' : () => (''),
        'kotlin.js.message_$external_prop_getter' : (_this) => _this.message,
        'kotlin.js.stack_$external_prop_getter' : (_this) => _this.stack,
        'kotlin.js.JsError_$external_class_instanceof' : (x) => x instanceof Error,
        'kotlin.js.then_$external_fun' : (_this, p0) => _this.then(p0),
        'kotlin.js.__convertKotlinClosureToJsClosure_((Js?)->Js?)' : (f) => getCachedJsObject(f, (p0) => wasmExports['__callFunction_((Js?)->Js?)'](f, p0)),
        'kotlin.js.then_$external_fun_1' : (_this, p0, p1) => _this.then(p0, p1),
        'kotlin.js.__convertKotlinClosureToJsClosure_((Js)->Js?)' : (f) => getCachedJsObject(f, (p0) => wasmExports['__callFunction_((Js)->Js?)'](f, p0)),
        'kotlin.js.catch_$external_fun' : (_this, p0) => _this.catch(p0),
        'kotlin.random.initialSeed' : () => ((Math.random() * Math.pow(2, 32)) | 0),
        'kotlin.wasm.internal.getJsClassName' : (jsKlass) => jsKlass.name,
        'kotlin.wasm.internal.instanceOf' : (ref, jsKlass) => ref instanceof jsKlass,
        'kotlin.wasm.internal.getConstructor' : (obj) => obj.constructor,
        'kotlin.time.Date_$external_fun' : () => new Date(),
        'kotlin.time.getTime_$external_fun' : (_this, ) => _this.getTime(),
        'kotlin.time.tryGetPerformance' : () => typeof globalThis !== 'undefined' && typeof globalThis.performance !== 'undefined' ? globalThis.performance : null,
        'kotlin.time.getPerformanceNow' : (performance) => performance.now(),
        'kotlin.time.dateNow' : () => Date.now(),
        'kotlinx.coroutines.tryGetProcess' : () => (typeof(process) !== 'undefined' && typeof(process.nextTick) === 'function') ? process : null,
        'kotlinx.coroutines.tryGetWindow' : () => (typeof(window) !== 'undefined' && window != null && typeof(window.addEventListener) === 'function') ? window : null,
        'kotlinx.coroutines.nextTick_$external_fun' : (_this, p0) => _this.nextTick(p0),
        'kotlinx.coroutines.error_$external_fun' : (_this, p0) => _this.error(p0),
        'kotlinx.coroutines.console_$external_prop_getter' : () => console,
        'kotlinx.coroutines.createScheduleMessagePoster' : (process) => () => Promise.resolve(0).then(process),
        'kotlinx.coroutines.__callJsClosure_(()->Unit)' : (f, ) => f(),
        'kotlinx.coroutines.createRescheduleMessagePoster' : (window) => () => window.postMessage('dispatchCoroutine', '*'),
        'kotlinx.coroutines.subscribeToWindowMessages' : (window, process) => {
            const handler = (event) => {
                if (event.source == window && event.data == 'dispatchCoroutine') {
                    event.stopPropagation();
                    process();
                }
            }
            window.addEventListener('message', handler, true);
        },
        'kotlinx.coroutines.setTimeout' : (window, handler, timeout) => window.setTimeout(handler, timeout),
        'kotlinx.coroutines.clearTimeout' : (handle) => { if (typeof clearTimeout !== 'undefined') clearTimeout(handle); },
        'kotlinx.coroutines.clearTimeout_$external_fun' : (_this, p0) => _this.clearTimeout(p0),
        'kotlinx.coroutines.setTimeout_$external_fun' : (p0, p1) => setTimeout(p0, p1),
        'kotlinx.browser.window_$external_prop_getter' : () => window,
        'kotlinx.browser.document_$external_prop_getter' : () => document,
        'org.w3c.dom.length_$external_prop_getter' : (_this) => _this.length,
        'org.w3c.dom.item_$external_fun' : (_this, p0) => _this.item(p0),
        'org.khronos.webgl.getMethodImplForUint8Array' : (obj, index) => { return obj[index]; },
        'org.khronos.webgl.slice_$external_fun' : (_this, p0, p1, isDefault0) => _this.slice(p0, isDefault0 ? undefined : p1, ),
        'org.khronos.webgl.Uint8Array_$external_fun' : (p0, p1, p2, isDefault0, isDefault1) => new Uint8Array(p0, isDefault0 ? undefined : p1, isDefault1 ? undefined : p2, ),
        'org.khronos.webgl.length_$external_prop_getter' : (_this) => _this.length,
        'org.khronos.webgl.buffer_$external_prop_getter' : (_this) => _this.buffer,
        'org.khronos.webgl.byteOffset_$external_prop_getter' : (_this) => _this.byteOffset,
        'org.khronos.webgl.byteLength_$external_prop_getter' : (_this) => _this.byteLength,
        'org.w3c.dom.clipboard.clipboardData_$external_prop_getter' : (_this) => _this.clipboardData,
        'org.w3c.dom.clipboard.ClipboardEvent_$external_class_instanceof' : (x) => x instanceof ClipboardEvent,
        'org.w3c.dom.css.cursor_$external_prop_setter' : (_this, v) => _this.cursor = v,
        'org.w3c.dom.css.height_$external_prop_setter' : (_this, v) => _this.height = v,
        'org.w3c.dom.css.left_$external_prop_setter' : (_this, v) => _this.left = v,
        'org.w3c.dom.css.opacity_$external_prop_setter' : (_this, v) => _this.opacity = v,
        'org.w3c.dom.css.outline_$external_prop_setter' : (_this, v) => _this.outline = v,
        'org.w3c.dom.css.position_$external_prop_setter' : (_this, v) => _this.position = v,
        'org.w3c.dom.css.top_$external_prop_setter' : (_this, v) => _this.top = v,
        'org.w3c.dom.css.whiteSpace_$external_prop_setter' : (_this, v) => _this.whiteSpace = v,
        'org.w3c.dom.css.width_$external_prop_setter' : (_this, v) => _this.width = v,
        'org.w3c.dom.css.setProperty_$external_fun' : (_this, p0, p1, p2, isDefault0) => _this.setProperty(p0, p1, isDefault0 ? undefined : p2, ),
        'org.w3c.dom.css.style_$external_prop_getter' : (_this) => _this.style,
        'org.w3c.dom.events.type_$external_prop_getter' : (_this) => _this.type,
        'org.w3c.dom.events.timeStamp_$external_prop_getter' : (_this) => _this.timeStamp,
        'org.w3c.dom.events.preventDefault_$external_fun' : (_this, ) => _this.preventDefault(),
        'org.w3c.dom.events.Event_$external_class_instanceof' : (x) => x instanceof Event,
        'org.w3c.dom.events.addEventListener_$external_fun' : (_this, p0, p1, p2) => _this.addEventListener(p0, p1, p2),
        'org.w3c.dom.events.__convertKotlinClosureToJsClosure_((Js)->Unit)' : (f) => getCachedJsObject(f, (p0) => wasmExports['__callFunction_((Js)->Unit)'](f, p0)),
        'org.w3c.dom.events.addEventListener_$external_fun_1' : (_this, p0, p1) => _this.addEventListener(p0, p1),
        'org.w3c.dom.events.addEventListener_$external_fun_2' : (_this, p0, p1) => _this.addEventListener(p0, p1),
        'org.w3c.dom.events.removeEventListener_$external_fun' : (_this, p0, p1) => _this.removeEventListener(p0, p1),
        'org.w3c.dom.events.removeEventListener_$external_fun_1' : (_this, p0, p1) => _this.removeEventListener(p0, p1),
        'org.w3c.dom.events.ctrlKey_$external_prop_getter' : (_this) => _this.ctrlKey,
        'org.w3c.dom.events.shiftKey_$external_prop_getter' : (_this) => _this.shiftKey,
        'org.w3c.dom.events.altKey_$external_prop_getter' : (_this) => _this.altKey,
        'org.w3c.dom.events.metaKey_$external_prop_getter' : (_this) => _this.metaKey,
        'org.w3c.dom.events.button_$external_prop_getter' : (_this) => _this.button,
        'org.w3c.dom.events.buttons_$external_prop_getter' : (_this) => _this.buttons,
        'org.w3c.dom.events.offsetX_$external_prop_getter' : (_this) => _this.offsetX,
        'org.w3c.dom.events.offsetY_$external_prop_getter' : (_this) => _this.offsetY,
        'org.w3c.dom.events.MouseEvent_$external_class_instanceof' : (x) => x instanceof MouseEvent,
        'org.w3c.dom.events.key_$external_prop_getter' : (_this) => _this.key,
        'org.w3c.dom.events.code_$external_prop_getter' : (_this) => _this.code,
        'org.w3c.dom.events.ctrlKey_$external_prop_getter_1' : (_this) => _this.ctrlKey,
        'org.w3c.dom.events.shiftKey_$external_prop_getter_1' : (_this) => _this.shiftKey,
        'org.w3c.dom.events.altKey_$external_prop_getter_1' : (_this) => _this.altKey,
        'org.w3c.dom.events.metaKey_$external_prop_getter_1' : (_this) => _this.metaKey,
        'org.w3c.dom.events.isComposing_$external_prop_getter' : (_this) => _this.isComposing,
        'org.w3c.dom.events.keyCode_$external_prop_getter' : (_this) => _this.keyCode,
        'org.w3c.dom.events.KeyboardEvent_$external_class_instanceof' : (x) => x instanceof KeyboardEvent,
        'org.w3c.dom.events.deltaX_$external_prop_getter' : (_this) => _this.deltaX,
        'org.w3c.dom.events.deltaY_$external_prop_getter' : (_this) => _this.deltaY,
        'org.w3c.dom.events.WheelEvent_$external_class_instanceof' : (x) => x instanceof WheelEvent,
        'org.w3c.dom.events.data_$external_prop_getter' : (_this) => _this.data,
        'org.w3c.dom.events.CompositionEvent_$external_class_instanceof' : (x) => x instanceof CompositionEvent,
        'org.w3c.dom.ShadowRootInit' : (mode) => { return { mode }; },
        'org.w3c.dom.AddEventListenerOptions_js_code' : (passive, once, capture) => { return { passive, once, capture }; },
        'org.w3c.dom.protocol_$external_prop_getter' : (_this) => _this.protocol,
        'org.w3c.dom.binaryType_$external_prop_setter' : (_this, v) => _this.binaryType = v,
        'org.w3c.dom.close_$external_fun' : (_this, p0, p1, isDefault0, isDefault1) => _this.close(isDefault0 ? undefined : p0, isDefault1 ? undefined : p1, ),
        'org.w3c.dom.send_$external_fun' : (_this, p0) => _this.send(p0),
        'org.w3c.dom.send_$external_fun_1' : (_this, p0) => _this.send(p0),
        'org.w3c.dom.Companion_$external_object_getInstance' : () => ({}),
        'org.w3c.dom.data_$external_prop_getter' : (_this) => _this.data,
        'org.w3c.dom.code_$external_prop_getter' : (_this) => _this.code,
        'org.w3c.dom.reason_$external_prop_getter' : (_this) => _this.reason,
        'org.w3c.dom.location_$external_prop_getter' : (_this) => _this.location,
        'org.w3c.dom.devicePixelRatio_$external_prop_getter' : (_this) => _this.devicePixelRatio,
        'org.w3c.dom.requestAnimationFrame_$external_fun' : (_this, p0) => _this.requestAnimationFrame(p0),
        'org.w3c.dom.__convertKotlinClosureToJsClosure_((Double)->Unit)' : (f) => getCachedJsObject(f, (p0) => wasmExports['__callFunction_((Double)->Unit)'](f, p0)),
        'org.w3c.dom.matchMedia_$external_fun' : (_this, p0) => _this.matchMedia(p0),
        'org.w3c.dom.dropEffect_$external_prop_setter' : (_this, v) => _this.dropEffect = v,
        'org.w3c.dom.setDragImage_$external_fun' : (_this, p0, p1, p2) => _this.setDragImage(p0, p1, p2),
        'org.w3c.dom.getData_$external_fun' : (_this, p0) => _this.getData(p0),
        'org.w3c.dom.setData_$external_fun' : (_this, p0, p1) => _this.setData(p0, p1),
        'org.w3c.dom.innerText_$external_prop_setter' : (_this, v) => _this.innerText = v,
        'org.w3c.dom.click_$external_fun' : (_this, ) => _this.click(),
        'org.w3c.dom.focus_$external_fun' : (_this, ) => _this.focus(),
        'org.w3c.dom.blur_$external_fun' : (_this, ) => _this.blur(),
        'org.w3c.dom.HTMLElement_$external_class_instanceof' : (x) => x instanceof HTMLElement,
        'org.w3c.dom.origin_$external_prop_getter' : (_this) => _this.origin,
        'org.w3c.dom.body_$external_prop_getter' : (_this) => _this.body,
        'org.w3c.dom.activeElement_$external_prop_getter' : (_this) => _this.activeElement,
        'org.w3c.dom.createElement_$external_fun' : (_this, p0, p1, isDefault0) => _this.createElement(p0, isDefault0 ? undefined : p1, ),
        'org.w3c.dom.hasFocus_$external_fun' : (_this, ) => _this.hasFocus(),
        'org.w3c.dom.id_$external_prop_setter' : (_this, v) => _this.id = v,
        'org.w3c.dom.clientWidth_$external_prop_getter' : (_this) => _this.clientWidth,
        'org.w3c.dom.clientHeight_$external_prop_getter' : (_this) => _this.clientHeight,
        'org.w3c.dom.setAttribute_$external_fun' : (_this, p0, p1) => _this.setAttribute(p0, p1),
        'org.w3c.dom.attachShadow_$external_fun' : (_this, p0) => _this.attachShadow(p0),
        'org.w3c.dom.getBoundingClientRect_$external_fun' : (_this, ) => _this.getBoundingClientRect(),
        'org.w3c.dom.matches_$external_prop_getter' : (_this) => _this.matches,
        'org.w3c.dom.addListener_$external_fun' : (_this, p0) => _this.addListener(p0),
        'org.w3c.dom.isConnected_$external_prop_getter' : (_this) => _this.isConnected,
        'org.w3c.dom.appendChild_$external_fun' : (_this, p0) => _this.appendChild(p0),
        'org.w3c.dom.dataTransfer_$external_prop_getter' : (_this) => _this.dataTransfer,
        'org.w3c.dom.DragEvent_$external_class_instanceof' : (x) => x instanceof DragEvent,
        'org.w3c.dom.identifier_$external_prop_getter' : (_this) => _this.identifier,
        'org.w3c.dom.clientX_$external_prop_getter' : (_this) => _this.clientX,
        'org.w3c.dom.clientY_$external_prop_getter' : (_this) => _this.clientY,
        'org.w3c.dom.top_$external_prop_getter' : (_this) => _this.top,
        'org.w3c.dom.left_$external_prop_getter' : (_this) => _this.left,
        'org.w3c.dom.remove_$external_fun' : (_this, ) => _this.remove(),
        'org.w3c.dom.Companion_$external_object_getInstance_1' : () => ({}),
        'org.w3c.dom.width_$external_prop_setter' : (_this, v) => _this.width = v,
        'org.w3c.dom.height_$external_prop_setter' : (_this, v) => _this.height = v,
        'org.w3c.dom.HTMLCanvasElement_$external_class_instanceof' : (x) => x instanceof HTMLCanvasElement,
        'org.w3c.dom.HTMLDivElement_$external_class_instanceof' : (x) => x instanceof HTMLDivElement,
        'org.w3c.dom.targetTouches_$external_prop_getter' : (_this) => _this.targetTouches,
        'org.w3c.dom.changedTouches_$external_prop_getter' : (_this) => _this.changedTouches,
        'org.w3c.dom.TouchEvent_$external_class_instanceof' : (x) => x instanceof TouchEvent,
        'org.w3c.dom.matches_$external_prop_getter_1' : (_this) => _this.matches,
        'org.w3c.dom.MediaQueryListEvent_$external_class_instanceof' : (x) => x instanceof MediaQueryListEvent,
        'org.w3c.fetch.status_$external_prop_getter' : (_this) => _this.status,
        'org.w3c.fetch.statusText_$external_prop_getter' : (_this) => _this.statusText,
        'org.w3c.fetch.headers_$external_prop_getter' : (_this) => _this.headers,
        'org.w3c.fetch.body_$external_prop_getter' : (_this) => _this.body,
        'org.w3c.fetch.get_$external_fun' : (_this, p0) => _this.get(p0),
        'org.w3c.fetch.Companion_$external_object_getInstance' : () => ({}),
        'org.w3c.performance.now_$external_fun' : (_this, ) => _this.now(),
        'org.w3c.performance.performance_$external_prop_getter' : (_this) => _this.performance,
        'io.ktor.utils.io.js.decode' : (decoder, buffer) => { try { return decoder.decode(buffer) } catch(e) { return null } },
        'io.ktor.utils.io.js.tryCreateTextDecoder' : (encoding, fatal) => { try { return new TextDecoder(encoding, { fatal: fatal }) } catch(e) { return null } },
        'io.ktor.utils.io.charsets.toJsArrayImpl' : (x) => new Int8Array(x),
        'io.ktor.util.hasNodeApi' : () => 
        (typeof process !== 'undefined' 
            && process.versions != null 
            && process.versions.node != null) ||
        (typeof window !== 'undefined' 
            && typeof window.process !== 'undefined' 
            && window.process.versions != null 
            && window.process.versions.node != null)
        ,
        'io.ktor.util.logging.getKtorLogLevel' : () => process ? process.env.KTOR_LOG_LEVEL : null,
        'io.ktor.util.logging.warn_$external_fun' : (_this, p0) => _this.warn(p0),
        'io.ktor.util.logging.debug_$external_fun' : (_this, p0) => _this.debug(p0),
        'io.ktor.util.logging.console_$external_prop_getter' : () => console,
        'io.ktor.util.date.Date_$external_fun' : () => new Date(),
        'io.ktor.util.date.Date_$external_fun_1' : (p0) => new Date(p0),
        'io.ktor.util.date.getTime_$external_fun' : (_this, ) => _this.getTime(),
        'io.ktor.util.date.getUTCDate_$external_fun' : (_this, ) => _this.getUTCDate(),
        'io.ktor.util.date.getUTCDay_$external_fun' : (_this, ) => _this.getUTCDay(),
        'io.ktor.util.date.getUTCFullYear_$external_fun' : (_this, ) => _this.getUTCFullYear(),
        'io.ktor.util.date.getUTCHours_$external_fun' : (_this, ) => _this.getUTCHours(),
        'io.ktor.util.date.getUTCMinutes_$external_fun' : (_this, ) => _this.getUTCMinutes(),
        'io.ktor.util.date.getUTCMonth_$external_fun' : (_this, ) => _this.getUTCMonth(),
        'io.ktor.util.date.getUTCSeconds_$external_fun' : (_this, ) => _this.getUTCSeconds(),
        'io.ktor.http.locationOrigin' : () => function() {
            var tmpLocation = null
            if (typeof window !== 'undefined') {
              tmpLocation = window.location
            } else if (typeof self !== 'undefined') {
              tmpLocation = self.location
            }
            var origin = ""
            if (tmpLocation) {
              origin = tmpLocation.origin
            }
            return origin && origin != "null" ? origin : "http://localhost"    
        }(),
        'io.ktor.client.engine.js.createBrowserWebSocket' : (urlString_capturingHack, protocols) => new WebSocket(urlString_capturingHack, protocols),
        'io.ktor.client.engine.js.createWebSocketNodeJs' : (socketCtor, urlString_capturingHack, headers_capturingHack, protocols) => new socketCtor(urlString_capturingHack, protocols, { headers: headers_capturingHack }),
        'io.ktor.client.engine.js.getKeys' : (headers) => Array.from(headers.keys()),
        'io.ktor.client.engine.js.eventAsString' : (event) => JSON.stringify(event, ['message', 'target', 'type', 'isTrusted']),
        'io.ktor.client.engine.js.compatibility.abortControllerCtorBrowser' : () => AbortController,
        'io.ktor.client.fetch.body_$external_prop_setter' : (_this, v) => _this.body = v,
        'io.ktor.client.fetch.headers_$external_prop_setter' : (_this, v) => _this.headers = v,
        'io.ktor.client.fetch.method_$external_prop_setter' : (_this, v) => _this.method = v,
        'io.ktor.client.fetch.redirect_$external_prop_setter' : (_this, v) => _this.redirect = v,
        'io.ktor.client.fetch.signal_$external_prop_setter' : (_this, v) => _this.signal = v,
        'io.ktor.client.fetch.signal_$external_prop_getter' : (_this) => _this.signal,
        'io.ktor.client.fetch.abort_$external_fun' : (_this, ) => _this.abort(),
        'io.ktor.client.fetch.fetch_$external_fun' : (p0, p1, isDefault0) => fetch(p0, isDefault0 ? undefined : p1, ),
        'io.ktor.client.fetch.getReader_$external_fun' : (_this, ) => _this.getReader(),
        'io.ktor.client.fetch.cancel_$external_fun' : (_this, p0, isDefault0) => _this.cancel(isDefault0 ? undefined : p0, ),
        'io.ktor.client.fetch.read_$external_fun' : (_this, ) => _this.read(),
        'io.ktor.client.fetch.done_$external_prop_getter' : (_this) => _this.done,
        'io.ktor.client.fetch.value_$external_prop_getter' : (_this) => _this.value,
        'io.ktor.client.plugins.websocket.tryGetEventDataAsString' : (data) => typeof(data) === 'string' ? data : null,
        'io.ktor.client.plugins.websocket.tryGetEventDataAsArrayBuffer' : (data) => data instanceof ArrayBuffer ? data : null,
        'io.ktor.client.utils.makeJsObject' : () => { return {}; },
        'io.ktor.client.utils.makeImport' : (name) => import(name),
        'io.ktor.client.utils.makeJsCall' : (func, arg) => func.apply(null, arg),
        'io.ktor.client.utils.jsObjectAssign' : () => Object.assign,
        'io.ktor.client.utils.makeJsNew' : (ctor) => new ctor(),
        'io.ktor.client.utils.getObjectField' : (obj, name) => obj[name],
        'io.ktor.client.utils.setObjectField' : (obj, name, value) => obj[name]=value,
        'io.ktor.client.utils.toJsArrayImpl' : (x) => new Uint8Array(x),
        'androidx.compose.runtime.internal.weakMap_js_code' : () => (new WeakMap()),
        'androidx.compose.runtime.internal.set_$external_fun' : (_this, p0, p1) => _this.set(p0, p1),
        'androidx.compose.runtime.internal.get_$external_fun' : (_this, p0) => _this.get(p0),
        'org.jetbrains.skiko.createContext_$external_fun' : (_this, p0, p1) => _this.createContext(p0, p1),
        'org.jetbrains.skiko.makeContextCurrent_$external_fun' : (_this, p0) => _this.makeContextCurrent(p0),
        'org.jetbrains.skiko.w3c.language_$external_prop_getter' : (_this) => _this.language,
        'org.jetbrains.skiko.w3c.userAgent_$external_prop_getter' : (_this) => _this.userAgent,
        'org.jetbrains.skiko.w3c.navigator_$external_prop_getter' : (_this) => _this.navigator,
        'org.jetbrains.skiko.w3c.performance_$external_prop_getter' : (_this) => _this.performance,
        'org.jetbrains.skiko.w3c.requestAnimationFrame_$external_fun' : (_this, p0) => _this.requestAnimationFrame(p0),
        'org.jetbrains.skiko.w3c.window_$external_object_getInstance' : () => window,
        'org.jetbrains.skiko.w3c.now_$external_fun' : (_this, ) => _this.now(),
        'org.jetbrains.skiko.w3c.width_$external_prop_getter' : (_this) => _this.width,
        'org.jetbrains.skiko.w3c.height_$external_prop_getter' : (_this) => _this.height,
        'org.jetbrains.skiko.w3c.HTMLCanvasElement_$external_class_instanceof' : (x) => x instanceof HTMLCanvasElement,
        'org.jetbrains.skiko.GL_$external_prop_getter' : () => _ref_Li9za2lrby5tanM_.GL,
        'org.jetbrains.skia.impl.FinalizationRegistry_$external_fun' : (p0) => new FinalizationRegistry(p0),
        'org.jetbrains.skia.impl.register_$external_fun' : (_this, p0, p1) => _this.register(p0, p1),
        'org.jetbrains.skia.impl.unregister_$external_fun' : (_this, p0) => _this.unregister(p0),
        'org.jetbrains.skia.impl._releaseLocalCallbackScope_$external_fun' : () => _ref_Li9za2lrby5tanM_c2tpa29BcGk._releaseLocalCallbackScope(),
        'org.jetbrains.skiko.getNavigatorInfo' : () => navigator.userAgentData ? navigator.userAgentData.platform : navigator.platform,
        'org.jetbrains.skiko.wasm.createDefaultContextAttributes' : () => {
            return {
                alpha: 1,
                depth: 1,
                stencil: 8,
                antialias: 0,
                premultipliedAlpha: 1,
                preserveDrawingBuffer: 0,
                preferLowPowerToHighPerformance: 0,
                failIfMajorPerformanceCaveat: 0,
                enableExtensionsByDefault: 1,
                explicitSwapControl: 0,
                renderViaOffscreenBackBuffer: 0,
                majorVersion: 2,
            }
        }
        ,
        'org.jetbrains.skiko.wasm.awaitSkiko_$external_prop_getter' : () => _ref_Li9za2lrby5tanM_.awaitSkiko,
        'androidx.compose.ui.text.intl.getUserPreferredLanguagesAsArray' : () => window.navigator.languages,
        'androidx.compose.ui.text.intl.parseLanguageTagToIntlLocale' : (languageTag) => new Intl.Locale(languageTag),
        'androidx.compose.ui.text.intl._language_$external_prop_getter' : (_this) => _this.language,
        'androidx.compose.ui.text.intl._region_$external_prop_getter' : (_this) => _this.region,
        'androidx.compose.ui.text.intl._baseName_$external_prop_getter' : (_this) => _this.baseName,
        'androidx.compose.ui.internal.weakMap_js_code' : () => (new WeakMap()),
        'androidx.compose.ui.internal.set_$external_fun' : (_this, p0, p1) => _this.set(p0, p1),
        'androidx.compose.ui.internal.get_$external_fun' : (_this, p0) => _this.get(p0),
        'androidx.compose.ui.platform.createClipboardItemWithPlainText' : (text) => [new ClipboardItem({'text/plain': new Blob([text], { type: 'text/plain' })})],
        'androidx.compose.ui.platform.emptyClipboardItems' : () => [new ClipboardItem({'text/plain': new Blob([''], { type: 'text/plain' })})],
        'androidx.compose.ui.platform.invalidClipboardItems' : () => [],
        'androidx.compose.ui.platform.warn' : (text) => { console.warn(text) },
        'androidx.compose.ui.platform.read_$external_fun' : (_this, ) => _this.read(),
        'androidx.compose.ui.platform.write_$external_fun' : (_this, p0) => _this.write(p0),
        'androidx.compose.ui.platform.writeText_$external_fun' : (_this, p0) => _this.writeText(p0),
        'androidx.compose.ui.platform.types_$external_prop_getter' : (_this) => _this.types,
        'androidx.compose.ui.platform.getType_$external_fun' : (_this, p0) => _this.getType(p0),
        'androidx.compose.ui.window.isMatchMediaSupported' : () => window.matchMedia != undefined,
        'androidx.compose.ui.events.withSignal' : (signal) => ({signal: signal}),
        'androidx.compose.ui.events.AbortController_$external_fun' : () => new AbortController(),
        'androidx.compose.ui.events.signal_$external_prop_getter' : (_this) => _this.signal,
        'androidx.compose.ui.platform.setBackingInputBox' : (container, left, top, width, height) => { 
            container.style.setProperty("--compose-internal-web-backing-input-left", left);
            container.style.setProperty("--compose-internal-web-backing-input-top", top);
            container.style.setProperty("--compose-internal-web-backing-input-width", width);
            container.style.setProperty("--compose-internal-web-backing-input-height", height)
         },
        'androidx.compose.ui.platform.isTypedEvent' : (evt) => !evt.metaKey && !evt.ctrlKey && evt.key.charAt(0) === evt.key,
        'androidx.compose.ui.platform.value_$external_prop_setter' : (_this, v) => _this.value = v,
        'androidx.compose.ui.platform.selectionStart_$external_prop_getter' : (_this) => _this.selectionStart,
        'androidx.compose.ui.platform.selectionEnd_$external_prop_getter' : (_this) => _this.selectionEnd,
        'androidx.compose.ui.platform.setSelectionRange_$external_fun' : (_this, p0, p1, p2, isDefault0) => _this.setSelectionRange(p0, p1, isDefault0 ? undefined : p2, ),
        'androidx.compose.ui.platform.inputType_$external_prop_getter' : (_this) => _this.inputType,
        'androidx.compose.ui.platform.data_$external_prop_getter' : (_this) => _this.data,
        'androidx.compose.ui.platform.isComposing_$external_prop_getter' : (_this) => _this.isComposing,
        'androidx.compose.ui.platform.deleteContentBackwardSize_$external_prop_getter' : (_this) => _this.deleteContentBackwardSize,
        'androidx.compose.ui.platform.deleteContentBackwardSize_$external_prop_setter' : (_this, v) => _this.deleteContentBackwardSize = v,
        'androidx.compose.ui.platform.InputEvent_$external_class_instanceof' : (x) => x instanceof InputEvent,
        'androidx.compose.ui.platform.isSecureContext' : () => window.isSecureContext === true,
        'androidx.compose.ui.platform.isFullClipboardApiSupported' : () => Boolean(
                window.navigator.clipboard && 
                window.navigator.clipboard.write && 
                window.navigator.clipboard.read && 
                typeof(ClipboardItem) !== 'undefined'
                )
            ,
        'androidx.compose.ui.platform.isFallbackWriteTextApiAvailable' : () => Boolean(window.navigator.clipboard && window.navigator.clipboard.writeText),
        'androidx.compose.ui.platform.getW3CClipboard' : () => window.navigator.clipboard,
        'androidx.compose.ui.platform.accessibility.removeAllChildrenOf' : (element) => { element.replaceChildren() },
        'androidx.compose.ui.platform.accessibility.setA11YAriaRole' : (element, ariaRoleId) => { 
                var roleValue = "";
                switch (ariaRoleId) {
                    case 0: // Role.Button
                        roleValue = "button";
                        break;
                    case 1: // Role.Checkbox
                        roleValue = "checkbox";
                        break;
                    case 2: // Role.Switch
                        roleValue = "switch";
                        break;
                    case 3: // Role.RadioButton
                        roleValue = "radio";
                        break;
                    case 4: // Role.Tab
                        roleValue = "tab";
                        break;
                    case 5: // Role.Image
                        roleValue = "img";
                        break;
                    case 6: // Role.DropdownList
                        roleValue = "menu";
                        break;
                    case 7: // heading https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/heading_role
                        roleValue = "heading";
                        break;
                    case 8: // https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/textbox_role
                        roleValue = "textbox";
                        break;
                    case 9: // https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/list_role
                        roleValue = "list";
                        break;
                    case 10: // https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/grid_role
                        roleValue = "grid";
                        break;
                    default:
                        break;
                }
                if (roleValue.length > 0) { 
                    element.setAttribute("role", roleValue);
                } else {
                    element.removeAttribute("role");
                }
             },
        'androidx.compose.ui.platform.accessibility.setSizeAndPosition' : (element, left, top, width, height) => { 
               element.style.left = "" + left + "px";
               element.style.top = "" + top + "px";
               element.style.width = "" + width + "px";
               element.style.height = "" + height + "px";
             },
        'androidx.compose.ui.window.documentIsVisible' : () => document.visibilityState === 'visible',
        'androidx.compose.ui.window.force_$external_prop_getter' : (_this) => _this.force,
        'androidx.compose.foundation.internal.isClipboardWriteSupported' : () => Boolean(window.navigator.clipboard && (window.navigator.clipboard.write || window.navigator.clipboard.writeText)),
        'androidx.compose.foundation.internal.isClipboardReadSupported' : () => Boolean(window.navigator.clipboard && window.navigator.clipboard.read),
        'androidx.compose.foundation.internal.getTextFromBlob' : (blob) => blob.text(),
        'androidx.compose.foundation.internal.doesJsArrayContainValue' : (jsArray, value) => jsArray.includes(value),
        'androidx.compose.foundation.text.EventListener' : (handler) => (event) => { handler(event) },
        'androidx.compose.material3.internal.weakMap_js_code' : () => (new WeakMap()),
        'androidx.compose.material3.internal.set_$external_fun' : (_this, p0, p1) => _this.set(p0, p1),
        'androidx.compose.material3.internal.get_$external_fun' : (_this, p0) => _this.get(p0)
    }
    
    // Placed here to give access to it from externals (js_code)
    let wasmInstance;
    let require; 
    let wasmExports;

    const isNodeJs = (typeof process !== 'undefined') && (process.release.name === 'node');
    const isDeno = !isNodeJs && (typeof Deno !== 'undefined')
    const isStandaloneJsVM =
        !isDeno && !isNodeJs && (
            typeof d8 !== 'undefined' // V8
            || typeof inIon !== 'undefined' // SpiderMonkey
            || typeof jscOptions !== 'undefined' // JavaScriptCore
        );
    const isBrowser = !isNodeJs && !isDeno && !isStandaloneJsVM && (typeof window !== 'undefined' || typeof self !== 'undefined');
    
    if (!isNodeJs && !isDeno && !isStandaloneJsVM && !isBrowser) {
      throw "Supported JS engine not detected";
    }
    
    const wasmFilePath = './tagohub.wasm';
    const importObject = {
        js_code,
        intrinsics: {
            
        },
        './skiko.mjs': imports['./skiko.mjs'],

    };
    
    try {
      if (isNodeJs) {
        const module = await import(/* webpackIgnore: true */'node:module');
        const importMeta = import.meta;
        require = module.default.createRequire(importMeta.url);
        const fs = require('fs');
        const url = require('url');
        const filepath = import.meta.resolve(wasmFilePath);
        const wasmBuffer = fs.readFileSync(url.fileURLToPath(filepath));
        const wasmModule = new WebAssembly.Module(wasmBuffer);
        wasmInstance = new WebAssembly.Instance(wasmModule, importObject);
      }
      
      if (isDeno) {
        const path = await import(/* webpackIgnore: true */'https://deno.land/std/path/mod.ts');
        const binary = Deno.readFileSync(path.fromFileUrl(import.meta.resolve(wasmFilePath)));
        const module = await WebAssembly.compile(binary);
        wasmInstance = await WebAssembly.instantiate(module, importObject);
      }
      
      if (isStandaloneJsVM) {
        const wasmBuffer = read(wasmFilePath, 'binary');
        const wasmModule = new WebAssembly.Module(wasmBuffer);
        wasmInstance = new WebAssembly.Instance(wasmModule, importObject);
      }
      
      if (isBrowser) {
        wasmInstance = (await WebAssembly.instantiateStreaming(fetch(new URL('./tagohub.wasm',import.meta.url).href), importObject)).instance;
      }
    } catch (e) {
      if (e instanceof WebAssembly.CompileError) {
        let text = `Please make sure that your runtime environment supports the latest version of Wasm GC and Exception-Handling proposals.
For more information, see https://kotl.in/wasm-help
`;
        if (isBrowser) {
          console.error(text);
        } else {
          const t = "\n" + text;
          if (typeof console !== "undefined" && console.log !== void 0) 
            console.log(t);
          else 
            print(t);
        }
      }
      throw e;
    }
    
    wasmExports = wasmInstance.exports;
    if (runInitializer) {
        wasmExports._initialize();
    }

    return { instance: wasmInstance,  exports: wasmExports };
}
