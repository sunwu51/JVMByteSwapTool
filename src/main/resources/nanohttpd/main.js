


var ws = null;
var logs = [];
var latestId = "";

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
            logMirror2.setValue(JSON.stringify(m.content,0,2))
            return
        }
        if (m.type === 'LOG') {
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
init()
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

document.getElementById("log-btn1").onclick = e => {
    logs = [];
    logMirror.setValue("");
}
document.getElementById("wt-btn").addEventListener("click", e => {
    var signature = document.getElementById("wt-input1").value;
    var printFormat = parseInt(document.getElementById('wt-radio1').value);
    if (signature.split("#").length === 2) {
        latestId = uuid();
        if (ws) {
            ws.send(JSON.stringify({
                id: latestId,
                timestamp: new Date().getTime(),
                type: "WATCH",
                printFormat,
                signature,
            }))
        } else {
            alert("ws连接关闭")
        }
    } else {
        alert("参数格式错误");
    }
})

document.getElementById("wt-btn2").addEventListener("click", e => {
    var signature = document.getElementById("wt-input2").value;
    var innerSignature = document.getElementById("wt-input3").value;
    var printFormat = parseInt(document.getElementById('wt-radio2').value);
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
            alert("ws连接关闭")
        }
    } else {
        alert("参数格式错误");
    }
})

document.getElementById("cb-btn").addEventListener("click", e => {
    var signature = document.getElementById("cb-input1").value;
    var paramTypesTxt = document.getElementById("cb-input2").value;
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
            alert("ws连接关闭")
        }
    } else {
        alert("参数格式错误");
    }
})

document.getElementById("cb-btn2").addEventListener("click", e => {
    var signature = document.getElementById("cb-input3").value;
    var paramTypesTxt = document.getElementById("cb-input4").value;
    var innerSignature = document.getElementById("cb-input5").value;

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
            alert("ws连接关闭")
        }
    } else {
        alert("参数格式错误");
    }
})

document.getElementById("ex-btn").addEventListener("click", e => {
    latestId = uuid();
    if (ws) {
        ws.send(JSON.stringify({
            id: latestId,
            timestamp: new Date().getTime(),
            type: "EXEC",
            body: exCode.getValue(),
        }))
    } else {
        alert("ws连接关闭")
    }
})


document.getElementById("rc-btn").addEventListener("click", async e => {
    latestId = uuid();
    if (ws) {
        var fileInput = document.getElementById("rc-input1");
        var className = document.getElementById("rc-input2").value;
        if (!fileInput.files || fileInput.files.length == 0) {
            alert("请选择文件")
            return
        }
        if (!className) {
            alert("请出入类名")
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
        alert("ws连接关闭")
    }
})

document.getElementById("log-btn2").addEventListener("click", async e => {
    latestId = uuid();
    if (ws) {
        var tail = document.getElementById("log-input1").value
        if (!tail || tail.length < 3){
            alert("uuid非法");
        }
        ws.send(JSON.stringify({
            id: latestId,
            timestamp: new Date().getTime(),
            type: "DELETE",
            uuid: tail
        }))
    } else {
        alert("ws连接关闭")
    }
})

document.getElementById("log-btn3").addEventListener("click", async e => {
    fetch("reset").then(res => res.text()).then(text=> alert("reset完成")).catch(e => {
        console.error(e);
        alert("reset失败");
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