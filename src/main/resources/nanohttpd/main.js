var ws = null;
var logs = [];
var latestId = "";
var logLevel = 0;


async function init() {
    var res = await fetch('/wsPort',{
        headers:{
            'content-type': "application/x-www-form-urlencoded"
        },
        method:"POST",
    });
    var port = await res.text();
    ws = new WebSocket(`ws://${window.location.hostname}:${port}`);

    ws.onmessage = (msg) => {
        var m = JSON.parse(msg.data);
        if (m.type === 'PONG') {
            jsonArea.setValue(JSON.stringify(m.content,0,2))
            return
        }
        if (m.type === 'LOG' && m.level >= logLevel) {
            logs.unshift(`[${m.id}][${moment(m.timestamp).format("YYYYMMDD HH:mm:ss")}]\n${m.content}`);
            while (logs.length > 100) {
                logs.pop();
            }
            logMirror.setValue(logs.join('\n'));
        }
    }
    ws.onclose = e=> console.log("ws closed", e)
    ws.onerror = console.error

}
// init()
setInterval(() => {
    if (!ws || ws.readyState === ws.CLOSED) {
        init();
    } else {
        ws.send(JSON.stringify({id: "_", type: "PING"}));
    }
}, 3000)



const dialog = document.querySelector('.dialog-width');
const openButton = dialog.nextElementSibling;
openButton.addEventListener('click', async () => {
    try {
        ws.send(JSON.stringify({id: "_", type: "PING"}));
    } catch (e){}
    dialog.show();
});

document.getElementById("clear-btn").onclick = e => {
    logs = [];
    logMirror.setValue("");
}
document.getElementById("wt-btn").addEventListener("click", e => {
    var signature = document.getElementById("wt-signature-input").value;
    var printFormat = parseInt(document.getElementById('wt-format-radio').value);
    var minCost = parseInt(document.getElementById("wt-min-cost-input").value);

    if (signature.split("#").length === 2) {
        latestId = uuid();
        if (ws) {
            ws.send(JSON.stringify({
                id: latestId,
                timestamp: new Date().getTime(),
                type: "WATCH",
                printFormat,
                signature,
                minCost
            }))
        } else {
            alert("ws closed");
        }
    } else {
        alert("params error");
    }
})

document.getElementById("owt-btn").addEventListener("click", e => {
    var signature = document.getElementById("owt-outer-signature-input").value;
    var innerSignature = document.getElementById("owt-inner-signature-input").value;
    var printFormat = parseInt(document.getElementById('owt-format-radio').value);
    if (signature.split("#").length === 2 && innerSignature.split("#").length === 2) {
        latestId = uuid();
        if (ws) {
            ws.send(JSON.stringify({
                id: latestId,
                timestamp: new Date().getTime(),
                type: "OUTER_WATCH",
                printFormat,
                signature,
                innerSignature,
            }))
        } else {
            alert("ws closed")
        }
    } else {
        alert("params error");
    }
})

document.getElementById("tc-btn").addEventListener("click", e => {
    var signature = document.getElementById("tc-signature-input").value;
    var minCost = parseInt(document.getElementById("tc-min-cost-input").value);
    var ignoreZero = document.getElementById("tc-ignore-zero").checked;
    if (signature.split("#").length === 2) {
        latestId = uuid();
        if (ws) {
            ws.send(JSON.stringify({
                id: latestId,
                timestamp: new Date().getTime(),
                type: "TRACE",
                signature,
                minCost,
                ignoreZero
            }))
        } else {
            alert("ws closed")
        }
    } else {
        alert("params error");
    }
})

document.getElementById("cb-btn").addEventListener("click", e => {
    var signature = document.getElementById("cb-signature-input").value;
    var paramTypesTxt = document.getElementById("cb-params-input").value;
    if (signature.split("#").length === 2) {
        latestId = uuid();
        if (ws) {
            ws.send(JSON.stringify({
                id: latestId,
                timestamp: new Date().getTime(),
                type: "CHANGE_BODY",
                className: signature.split("#")[0],
                method: signature.split("#")[1],
                body: cbCode.getValue(),
                paramTypes: paramTypesTxt.split(",").map(it => it.trim())
                    .filter(it => it.length !== 0),
            }))
        } else {
            alert("ws closed")
        }
    } else {
        alert("params error");
    }
})

document.getElementById("ocb-btn").addEventListener("click", e => {
    var signature = document.getElementById("ocb-outer-signature-input").value;
    var paramTypesTxt = document.getElementById("ocb-outer-params-input").value;
    var innerSignature = document.getElementById("ocb-inner-signature-input").value;

    if (signature.split("#").length === 2 && innerSignature.split("#").length === 2) {
        latestId = uuid();
        if (ws) {
            ws.send(JSON.stringify({
                id: latestId,
                timestamp: new Date().getTime(),
                type: "CHANGE_RESULT",
                className: signature.split("#")[0],
                method: signature.split("#")[1],
                innerClassName: innerSignature.split("#")[0],
                innerMethod: innerSignature.split("#")[1],
                body: cbCode2.getValue(),
                paramTypes: paramTypesTxt.split(",").map(it => it.trim())
                    .filter(it => it.length !== 0),
            }))
        } else {
            alert("ws closed")
        }
    } else {
        alert("params error");
    }
})

document.getElementById("ex-btn").addEventListener("click", e => {
    latestId = uuid();
    var mode = parseInt(execModeSelect.value)
    if (ws) {
        ws.send(JSON.stringify({
            id: latestId,
            timestamp: new Date().getTime(),
            type: "EXEC",
            mode,
            body: exCode2.getValue(),
        }))
    } else {
        alert("ws closed")
    }
})


document.getElementById("rc-btn").addEventListener("click", async e => {
    latestId = uuid();
    if (ws) {
        var fileInput = document.getElementById("rc-file-input");
        var className = document.getElementById("rc-class-input").value;
        if (!fileInput.files || fileInput.files.length == 0) {
            alert("Please select a file")
            return
        }
        if (!className) {
            alert("Please select a class")
            return
        }
        fileInput.disabled = true;
        try {
            var file = fileInput.files[0];
            var b64 = await fileToBase64(file)
            ws.send(JSON.stringify({
                id: latestId,
                timestamp: new Date().getTime(),
                type: "REPLACE_CLASS",
                content: b64,
                className
            }))
        } catch (e) {} finally {
            fileInput.disabled = false;
        }
    } else {
        alert("ws closed")
    }
})

document.getElementById("del-btn").addEventListener("click", async e => {
    latestId = uuid();
    if (ws) {
        var tail = document.getElementById("uuid-input").value
        if (!tail || tail.length < 3){
            alert("uuid invalid");
        }
        ws.send(JSON.stringify({
            id: latestId,
            timestamp: new Date().getTime(),
            type: "DELETE",
            uuid: tail
        }))
    } else {
        alert("ws closed")
    }
})

document.getElementById("reset-btn").addEventListener("click", async e => {
    fetch("reset").then(res => res.text()).then(text=> alert("reset finish~")).catch(e => {
        console.error(e);
        alert("reset error");
    });
})


function fileToBase64(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => {
            const base64String = reader.result.split(',')[1];
            resolve(base64String);
        };
        reader.onerror = (error) => {
            reject(error);
        };
        reader.readAsDataURL(file);
    });
}


function uuid() {
    var s = [];
    var hexDigits = "0123456789abcdef";
    for (var i = 0; i < 4; i++) {
        s[i] = hexDigits.substr(Math.floor(Math.random() * 0x10), 1);
    }
    var uuid = s.join("");
    return uuid;
}