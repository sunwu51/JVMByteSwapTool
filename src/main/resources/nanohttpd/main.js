


var ws = null;
async function init() {
    var res = await fetch('/wsPort',{
        headers:{
            'content-type': "application/x-www-form-urlencoded"
        },
        method:"POST",
    });
    var port = await res.text();
    ws = new WebSocket(`ws://${window.location.hostname}:${port}`);
    setInterval(() => ws.send(JSON.stringify({id: "_", type: "PING"})), 1000);

    ws.onmessage = (msg) => {
        var m = JSON.parse(msg.data);
        if (m.type === 'PONG') {
            var {activeMethods} = m;
            document.getElementById("activeMethods").innerText = activeMethods.join(", ")
            return
        }
        if (m.type === 'LOG') {
            logs.unshift(`[${m.id}][${moment(m.timestamp).format("YYYYMMDD HH:mm:ss")}]${m.content}`);
            while (logs.length > 100) {
                logs.pop();
            }
            logMirror.setValue(logs.join('\n'));
        }
    }

    ws.onclose = () => {
        console.log("ws close... try to reconnect...")
        init()
    }
}
init();


var latestId = "";
// 当点击watch的时候
document.getElementById("wt-btn").addEventListener("click", e => {
    var signature = document.getElementById("wt-input1").value;
    var useJson = document.getElementById('wt-radio1').value == 2;
    if (signature.split("#").length === 2) {
        latestId = uuid();
        if (ws) {
            ws.send(JSON.stringify({
                id: latestId,
                timestamp: new Date().getTime(),
                type: "WATCH",
                useJson,
                signature,
            }))
        } else {
            alert("ws连接关闭")
        }
    } else {
        alert("参数格式错误");
    }
})

// 当点击changebody的时候
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

// 当点击execute的时候
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






function uuid() {
    var s = [];
    var hexDigits = "0123456789abcdef";
    for (var i = 0; i < 4; i++) {
        s[i] = hexDigits.substr(Math.floor(Math.random() * 0x10), 1);
    }
    var uuid = s.join("");
    return uuid;
}