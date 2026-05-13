let host = '118.31.36.131';//aliserver
let port = 9001;
let topic = '#';
let useTLS = false;
let cleansession = true;
// cleansession = false; // 设置为false以保留会话

let reconnectTimeout = 5000;
let tempData = [];
let humiData = [];

let mqtt;

let SHOW_INTERVAL_COUNT = 3;
let show_interval = 0;

let InstData_map = new Map();
InstData_map.set('time', '');
InstData_map.set('stat', '');
InstData_map.set('temp', '');
InstData_map.set('humi', '');

InstData_map.set('tt', '');//target temperature
InstData_map.set('th', '');//target humidity
InstData_map.set('td', '');//temperature deviation
InstData_map.set('hd', '');//humidity deviation

// InstData_map.set('ts', '');//temperature status
// InstData_map.set('hs', '');//humidity status

InstData_map.set('cs', '');//control status
InstData_map.set('cm', '');//control mode
InstData_map.set('ac', '');//air circulation(acc1,acc2,acc3,acc4)

InstData_map.set('wifi_quality', 0);


var isConnected = false;
var uiNeedsUpdate = false; // 标记是否需要更新UI（降低更新频率）

function MQTTconnect() {
    if (typeof path == "undefined") {
        path = '/';
    }
    mqtt = new Paho.MQTT.Client(host, port, path, "mqtt_dash" + parseInt(Math.random() * 1000, 10));

    // mqtt = new Paho.MQTT.Client(host, port, path, "mqtt_dash_001");

    let options = {
        // keepAliveInterval: 10,
        timeout: 5,
        useSSL: useTLS,
        cleanSession: cleansession,
        onSuccess: onConnect,
        onFailure: function (message) {
            // $('#status').html("Connection failed: " + message.errorMessage + "Retrying...")
            //     .attr('class', 'alert alert-danger');

            // $('#status').html("连接失败: " + message.errorMessage + "重试中 ...").attr('class', 'alert alert-danger');
            $('#status').html('<i class="bi bi-reception-0" style="color:white"></i>');
            
            setTimeout(MQTTconnect, reconnectTimeout);
        }
    };

    mqtt.onConnectionLost = onConnectionLost;
    mqtt.onMessageArrived = onMessageArrived;
    console.log("Host: " + host + ", Port: " + port + ", Path: " + path + " TLS: " + useTLS);
    mqtt.connect(options);
};


function onConnect() {
    isConnected = true;
    //$('#status').html('<i class="bi bi-info-circle"></i>  已经连接到服务器.').attr('class', 'alert alert-success');
    $('#status').html('<i class="bi bi-reception-0" style="color:white"></i>');

    mqtt.subscribe(topic, { qos: 1 });
    // $('#topic').html(topic);


};


function onConnectionLost(response) {
    isConnected = false;
    //fuck!
    //$('#status').html('重新连接服务器...').attr('class', 'alert alert-warning');
    setTimeout(MQTTconnect, reconnectTimeout);
}


function parseDevInfoData(data) {
    console.log("********************************************");
    console.log(data);
    console.log("********************************************");
    // var data = "5568ee01020100100118060503130a010101027700c8000a03c000320101000a00000000000000c8000a03c000320001000a00010107027800c8000a03c000320001000a00000000000000c8000a03c000320001000a00010104027700c8000a03c000320101000a00aa";

    let wifi_quality = parseInt('0x' + data.substring(data.length - 2, data.length));
    InstData_map.set('wifi_quality', wifi_quality);

    let time = (parseInt('0x' + data.substring(13*2, 13*2 + 2)))+ ":" + (parseInt('0x' + data.substring(14*2, 14*2 + 2)));
    console.log("time:" + time)
    
    let stat = parseInt('0x' + data.substring(5*2, 5*2 +2)) == 1 ? "<span style='color:#2ECC40'>运行</span>" : "<span style='color:#dc3545'>停止</sapn>";
    console.log("stat:" + stat)

    let hasSensor = (parseInt('0x' + data.substring(15*2+18*2*4, 15*2+18*2*4 + 2)));

    let temp = (parseInt('0x' + data.substring(15*2+18*2*4+1*2, 15*2+18*2*4+1*2 + 4))/10).toFixed(1);
    let humi = (parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 4 , 15*2+18*2*4+1*2 + 8))/10).toFixed(1);

    let tt = (parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 8 , 15*2+18*2*4+1*2 + 12))/10).toFixed(1);
    let td = (parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 12 , 15*2+18*2*4+1*2 + 16))/10).toFixed(1);

    let th = (parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 16 , 15*2+18*2*4+1*2 + 20))/10).toFixed(1);
    let hd = (parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 20 , 15*2+18*2*4+1*2 + 24))/10).toFixed(1);

    let cm;
    let cs;

    // 第14字节，表示控制模式（1表示循环，0表示自动），显示时应判断传感器是否存在，存在显示对应的模式，不存在不显示。
    // # 第15字节，表示控制策略（1表示节能， 0表示无（不显示））， 显示时应判断传感器是否存在，存在显示节能，不存在不显示。
    console.log("hasSensor:" + hasSensor);
    if(hasSensor == 0)
    {
        cm = "-";
        cs = "-";
    }
    else
    {
        console.log("show cm & cs ...");

        let cm1 = parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 24 , 15*2+18*2*4+1*2 + 26)) == 1 ? "循环 <i class='bi bi-recycle'></i>" : "自动";
        let cm2 = parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 26 , 15*2+18*2*4+1*2 + 28)) == 1 ? "节能 <i class='bi bi-tree'></i>" : "";
        cm = cm1 + "    " + cm2;
        // # 第16字节，表示空调保护状态，以位的形式表示制冷与加热（二进制00，10，01，分别对应没有保护，制冷保护，加热保记）， 
        // 显示时应判断传感器是否存在，存在时00不显示，10显示制冷保护，10显示加热保护。
        // if int('0x' + data_list[idx+15], 16) == 0: #00
        // temp_status_info = ''
        // elif int('0x' + data_list[idx+15], 16) == 2: #10
        // temp_status_info = "制冷保护 <i class='glyphicon glyphicon-lock'></i>"
        // elif int('0x' + data_list[idx+15], 16) == 1: #01
        // temp_status_info = "加热保护 <i class='glyphicon glyphicon-lock'></i>"
        // else:
        // temp_status_info = ''

        // let ts = 0;
        // let ts_val = parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 28 , 15*2+18*2*4+1*2 + 30));

        // if(ts_val == 0)
        // {
        //     ts = '';
        // }
        // else if(ts_val == 2)
        // {
        //     ts = "制冷保护";
        // }
        // else if(ts_val == 1)
        // {
        //     ts = "加热保护";
        // }
        // else
        // {
        //     ts = '';
        // }


        // bin_str="{:>08}".format(bin(int('0x' + data_list[idx+16], 16))[2:])
        // print('---------------------------------------')
        // print(bin_str)
        // print('----------------------------------------')
        // bin_str_list = list(bin_str)
        // # cos_str = ''

        // # ['0', '0', '0', '0', '0', '1', '0', '1']
        // # 从低到高每个状态为：加热，制冷，化霜，加湿，抽湿.
        // if bin_str_list[7] == '1':
        //     # cos_str += "加热 "
        //     temp_status_info += "加热 <i class='glyphicon glyphicon-fire'></i>"
        // if bin_str_list[6] == '1':
        //     # cos_str += "制冷 "
        //     temp_status_info += "制冷 <i class='glyphicon glyphicon-asterisk'></i>"
        // if bin_str_list[5] == '1':
        //     # cos_str += "化霜 "
        //     temp_status_info += "化霜 "
        // if bin_str_list[4] == '1':
        //     # cos_str += "加湿 "      
        //     humi_status_info += "加湿 <i class='glyphicon glyphicon-tint'></i>"  
        // if bin_str_list[3] == '1':
        //     # cos_str += "抽湿 " 
        //     humi_status_info += "抽湿 <i class='glyphicon glyphicon-log-out'></i>"

        let ts = 0;
        let ts_val = parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 28 , 15*2+18*2*4+1*2 + 30));

        if(ts_val == 0)
        {
            ts = '';
        }
        else if(ts_val == 2)
        {
            ts = "制冷保护 <i class='bi bi-shield-shaded'></i>    ";
        }
        else if(ts_val == 1)
        {
            ts = "加热保护  <i class='bi bi-shield-shaded'></i>    ";
        }
        else
        {
            ts = '';
        }


        cs = "";
        let cs_val = parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 30 , 15*2+18*2*4+1*2 + 32));
        let cs_bits = cs_val.toString(2).padStart(8, '0');
        if (cs_bits[7] == '1')
        {
            cs += "加热 <i class='bi bi-fire'></i>    ";
        }
        if (cs_bits[6] == '1')
        {
            cs += "制冷 <i class='bi bi-snow'></i>    ";
        }
        if (cs_bits[5] == '1')
        {
            cs += "化霜 <i class='bi bi-cloud-haze2'></i>    ";
        }
        if (cs_bits[4] == '1')
        {
            cs += "加湿 <i class='bi bi-droplet-fill'></i>    ";
        }
        if (cs_bits[3] == '1')
        {
            cs += "抽湿 <i class='bi bi-droplet-half'></i>    ";
        }

        cs = ts + " " + cs;
    }


    // #判断CH5, 即综合通道第18字节，显示当前通道有无空调控制模块(为0时表示没有,为1时表示CH1存在‘绿色’ 其他值为不存在‘红色’)
    // if ch == 5:
    //     print("CH:{} ACCM:{}".format(ch, int('0x' + data_list[idx+17], 16) ))
    //     if int('0x' + data_list[idx+17], 16) != 0:
    //         dashboard_get_info1_dict.update({"accm_1" : "<i class='fa fa-circle accm1'></i>" })
    //         dashboard_get_info1_dict.update({"accm_2" : "<i class='fa fa-circle accm2'></i>" })
    //         dashboard_get_info1_dict.update({"accm_3" : "<i class='fa fa-circle accm2'></i>" })
    //         dashboard_get_info1_dict.update({"accm_4" : "<i class='fa fa-circle accm2'></i>" })
    //     else:
    //         dashboard_get_info1_dict.update({"accm_1" : "<i class='fa fa-circle accm2'></i>" })
    //         dashboard_get_info1_dict.update({"accm_2" : "<i class='fa fa-circle accm2'></i>" })
    //         dashboard_get_info1_dict.update({"accm_3" : "<i class='fa fa-circle accm2'></i>" })
    //         dashboard_get_info1_dict.update({"accm_4" : "<i class='fa fa-circle accm2'></i>" })

    let ac = parseInt('0x' + data.substring(15*2+18*2*4+1*2 + 32 , 15*2+18*2*4+1*2 + 34));
  
    InstData_map.set('time', time);
    InstData_map.set('stat', stat);
    InstData_map.set('temp', temp);
    InstData_map.set('humi', humi);

    InstData_map.set('tt', tt);
    InstData_map.set('td', td);

    InstData_map.set('th', th);
    InstData_map.set('hd', hd);

    InstData_map.set('cm', cm);
    // InstData_map.set('ts', ts);
    InstData_map.set('cs', cs);
    InstData_map.set('ac', ac);


    // 5568ee3d020100740119040c06112a
    // 0100eb015100c8000a03c000320100000a00
    // 000000000000c8000a03c000320000000a00
    // 000000000000c8000a03c000320000000a00
    // 000000000000c8000a03c000320000000a00
    // 0100eb015100c8000a03c000320100000a00
    // aa

    for (let ch = 1; ch <= 4; ch++) {
  
        let ch_temp = (parseInt('0x' + data.substring(15*2 + (ch-1)*18*2  + 1*2, 15*2 + (ch-1)*18*2 + 1*2 + 4))/10).toFixed(1);
        let ch_humi =  (parseInt('0x' + data.substring(15*2 + (ch-1)*18*2 + 1*2 + 4 , 15*2 + (ch-1)*18*2  + 1*2 + 8))/10).toFixed(1);
        
        InstData_map.set(`ch${ch}_temp`, ch_temp==0.0?'-':ch_temp);
        InstData_map.set(`ch${ch}_humi`, ch_humi==0.0?'-':ch_humi);
    }

}

function updateChannelDisplays() {
    for (let ch = 1; ch <= 4; ch++) {
        $(`#ch${ch}_temp`).html(InstData_map.get(`ch${ch}_temp`));
        $(`#ch${ch}_humi`).html(InstData_map.get(`ch${ch}_humi`));
    }
}

function updateUIDisplay() {
    if (!uiNeedsUpdate) return;
    uiNeedsUpdate = false;

    // 更新温湿度显示
    if(parseFloat(InstData_map.get('humi')) < 100)
    {
        $('#humi').html(InstData_map.get('humi'));
    }

    if(parseFloat(InstData_map.get('temp')) < 50)
    {
        $('#temp').html(InstData_map.get('temp'));
    }

    $('#stat').html(InstData_map.get('stat'));
    $('#tt').html(InstData_map.get('tt'));
    $('#td').html(InstData_map.get('td'));
    $('#th').html(InstData_map.get('th'));
    $('#hd').html(InstData_map.get('hd'));
    $('#cm').html(InstData_map.get('cm'));
    $('#cs').html(InstData_map.get('cs'));

    // 更新空调状态
    if(InstData_map.get('ac') == 1)
    {
        $('#accm_1').html("<i class='bi bi-record-fill green'></i>");
        $('#accm_2').html("<i class='bi bi-record-fill red'></i>");
        $('#accm_3').html("<i class='bi bi-record-fill red'></i>");
        $('#accm_4').html("<i class='bi bi-record-fill red'></i>");
    }
    else
    {
        $('#accm_1').html("<i class='bi bi-record-fill red'></i>");
        $('#accm_2').html("<i class='bi bi-record-fill red'></i>");
        $('#accm_3').html("<i class='bi bi-record-fill red'></i>");
        $('#accm_4').html("<i class='bi bi-record-fill red'></i>");
    }

    // 更新WiFi信号强度
    let wifi_quality = InstData_map.get('wifi_quality');
    if(wifi_quality > 100)
    {
        $('#status').html('<i class="bi bi-reception-4" style="color:white"></i> ' + '100%');
    }
    else if(wifi_quality > 90)
    {
        $('#status').html('<i class="bi bi-reception-4" style="color:white"></i> '+ wifi_quality +'%');
    }
    else if(wifi_quality > 70)
    {
        $('#status').html('<i class="bi bi-reception-3" style="color:white"></i> '+ wifi_quality +'%');
    }
    else if(wifi_quality > 50)
    {
        $('#status').html('<i class="bi bi-reception-2" style="color:white"></i> '+ wifi_quality +'%');
    }
    else if(wifi_quality > 30)
    {
        $('#status').html('<i class="bi bi-reception-1" style="color:white"></i> '+ wifi_quality +'%');
    }
    else
    {
        $('#status').html('<i class="bi bi-reception-0" style="color:white"></i>');
    }

    // 更新通道显示
    updateChannelDisplays();

    // 更新图表
    drawChart_temp(tempData);
    drawChart_humi(humiData);
}


function parseDevStateData(data) {
    // data = "55 03 CF XX AA";
    // let str2_list = data.split(" ")
    // console.log(str2_list)
    // let stat = parseInt('0x' + str2_list[3]);
    //5503CFXXAA
    let stat = parseInt('0x' + data.substring(3*2, 3*2 +2)) == 1 ? "<span style='color:#2ECC40'>运行</span>" : "<span style='color:#dc3545'>停止</sapn>";
    InstData_map.set('stat', stat);
}



function onMessageArrived(message) {
    // 记录消息到达时间
    const receiveTime = Date.now();
    let topic = message.destinationName;
    let payload = message.payloadString;
    console.log("Topic: " + topic + ", Message payload: " + payload);
    // $('#message').html(topic + ', ' + payload);
    // $('#topic').html(topic);
    // $('#message').html(payload);

    var xds_inst_id = localStorage.getItem('xds_inst_id');

    switch(topic)
    {
        case 'dev/'+xds_inst_id+'/info':
            console.log('topic: dev/'+xds_inst_id+'/info');
            parseDevInfoData(payload);

            // 更新图表数据
            tempData.push({
                "timestamp": InstData_map.get('time'),
                "temperature": parseFloat(InstData_map.get('temp'))
            });
            if (tempData.length >= 10) {
                tempData.shift()
            }

            humiData.push({
                "timestamp": InstData_map.get('time'),
                "humi": parseFloat(InstData_map.get('humi'))
            });
            if (humiData.length >= 10) {
                humiData.shift()
            }

            // 设置标志位，触发定时器更新UI
            uiNeedsUpdate = true;
            break;

        case 'dev/'+xds_inst_id+'/state':
            console.log('topic: dev/'+xds_inst_id+'/state');
            parseDevStateData(payload);
            break;

        case 'dev/'+xds_inst_id+'/ctrl':
            console.log('topic: dev/'+xds_inst_id+'/ctrl');
            console.log(payload);
            break;

        case 'dev/'+xds_inst_id+'/common_command':
            console.log('topic: dev/'+xds_inst_id+'/common_command');
            console.log(payload);
            //   // # 6, 获取各通道的控制设置数据
            //   // # 格式:55 03 F9 xx AA 其中xx 取值（00,01,02,03）
            //   // # 返回:  55 1B F9 aa xx xx xx ……… AA

            // if payload start with 551BF9
            if (payload.startsWith('551bf900'))  // 6, 获取各通道的控制设置数据 
            {
                console.log('6, 获取各通道的控制设置数据');
                //payload like this:
                // 551bf900011d03c0000a003200030003002800320003002800000000aa
                
                data = payload.substring(8, payload.length - 2).match(/.{1,2}/g);
                
                $("#target_temp").val(parseInt(data[0]+data[1], 16)/10);
                $("#target_humi").val(parseInt(data[2]+data[3], 16)/10);
                $("#ctl_temp").val(parseInt(data[4]+data[5], 16)/10);
                $("#ctl_humi").val(parseInt(data[6]+data[7], 16)/10);
                $("#ctl_temp_time").val(parseInt(data[8]+data[9], 16));
                $("#ctl_humi_time").val(parseInt(data[10]+data[11], 16));
                $("#heat_time").val(parseInt(data[12]+data[13], 16));
                $("#humi_time").val(parseInt(data[14]+data[15], 16));
                $("#frost_time").val(parseInt(data[16]+data[17], 16));
                $("#stop_time").val(parseInt(data[18]+data[19], 16));
            }   

            $('#responseTextarea').val(payload.toUpperCase()); // for debug window
            break;
        

        default:
            console.log('Info: Data do not match the MQTT topic.');
            break;
    }


};

function drawChart_temp(data) {
    var ctx = document.getElementById("tempChart").getContext("2d");

    var temperatures = [];
    var timestamps = [];

    data.forEach(function(entry) {
        temperatures.push(parseFloat(entry.temperature.toFixed(1)));
        timestamps.push(entry.timestamp);
    });

    new Chart(ctx, {
        type: 'line',
        data: {
            labels: timestamps,
            datasets: [{
                label: '温度',
                data: temperatures,
                backgroundColor: 'rgba(255,99,132,0.2)',
                borderColor: 'rgb(255,99,132)',
                borderWidth: 2,
                fill: false
            }]
        },
        options: {
            scales: {
                yAxes: [{
                    ticks: {
                        min: 0,
                        max: 50,
                        stepSize: 10,
                        callback: function(value) {
                            return value.toFixed(1);
                        }
                    }
                }]
            },
            tooltips: {
                callbacks: {
                    label: function(tooltipItem) {
                        return tooltipItem.yLabel.toFixed(1);
                    }
                }
            },
            legend: {
                display: false
            }
        }
    });
}

function drawChart_humi(data) {
    var ctx = document.getElementById("humiChart").getContext("2d");

    var humidity = [];
    var timestamps = [];

    data.forEach(function(entry) {
        humidity.push(parseFloat(entry.humi.toFixed(1)));
        timestamps.push(entry.timestamp);
    });

    new Chart(ctx, {
        type: 'line',
        data: {
            labels: timestamps,
            datasets: [{
                label: '湿度',
                data: humidity,
                backgroundColor: 'rgba(173,216,230,0.2)',
                borderColor: 'rgb(173,216,230)',
                borderWidth: 2,
                fill: false
            }]
        },
        options: {
            scales: {
                yAxes: [{
                    ticks: {
                        min: 0,
                        max: 100,
                        stepSize: 20,
                        callback: function(value) {
                            return value.toFixed(1);
                        }
                    }
                }]
            },
            tooltips: {
                callbacks: {
                    label: function(tooltipItem) {
                        return tooltipItem.yLabel.toFixed(1);
                    }
                }
            },
            legend: {
                display: false
            }
        }
    });
}

function sendMessage(topic, message) {
    console.log("Sending message to topic:", topic);
    console.log("Message payload:", message);
    console.log("Type of topic:", typeof topic);
    console.log("Type of message:", typeof message);
    // alert("topic: " + topic + ", message: " + message);

    if (isConnected) {
        try {
            var mqttMessage = new Paho.MQTT.Message(message);
            mqttMessage.destinationName = topic;
            // 设置QoS为1并添加消息ID跟踪
            mqttMessage.qos = 1;
            mqttMessage.retained = false;
            mqttMessage.messageId = Date.now();
            console.log("Sending message ID:", mqttMessage.messageId);
            mqtt.send(mqttMessage);
            console.log("Message sent successfully.");
        } catch (error) {
            console.error("Error sending message:", error);
            // alert("Error sending message: " + error.message);
        }
    } else {
        console.log("Can't send message, not connected to MQTT server.");
        // alert("Can't send message, not connected to MQTT server.");
    }
}

//QR JS BEGIN
var img = null;
var blist = [];


function scaned(r){
	document.getElementById('devInstId').value = r;
}

// 打开二维码扫描界面 
function openBarcode(){
    if (window.AndroidScanner && typeof window.AndroidScanner.showScanOptions === 'function') {
        window.AndroidScanner.showScanOptions();
        return;
    }

    if (!window.plus) {
        alert('当前环境不支持扫码录入');
        return;
    }

    createWithoutTitle('barcode_scan.html', {
        titleNView:{
            type: 'float',
            backgroundColor: 'rgba(215,75,40,0.3)',
            titleText: '扫一扫',
            titleColor: '#FFFFFF',
            autoBackButton: true,
            buttons: [{
                fontSrc: 'helloh5.ttf',
                text: '相册',
                fontSize: '18px',
                onclick: 'javascript:scanPicture()'
            }]
        }
    });
}

//QR JS END

function openNativeDeviceEntry() {
    if (window.AndroidScanner && typeof window.AndroidScanner.openNativeDeviceEntry === 'function') {
        window.AndroidScanner.openNativeDeviceEntry();
        return;
    }

    alert('当前环境不支持原生设备录入');
}

function applyNativeDeviceData(deviceId, deviceName, deviceListJson) {
    localStorage.setItem('xds_inst_id', deviceId || '');
    localStorage.setItem('xds_inst_name', deviceName || '');
    localStorage.setItem('device_list', deviceListJson || '[]');

    $('#devInstId').val(deviceId || '');
    $('#devInstName').val(deviceName || '');

    if (deviceId) {
        $('#esp8266_mac').html(deviceId.split(':').slice(3).join('') + ' | ' + (deviceName || ''));
    }

    if (typeof loadDeviceList === 'function') {
        loadDeviceList();
    }

    $('#connectModal').modal('hide');
}


$(document).ready(function () {
    MQTTconnect();

    drawChart_temp(tempData);
    drawChart_humi(humiData);

    // 每秒更新一次UI（降低更新频率，减少主线程占用）
    setInterval(updateUIDisplay, 1000);

    // get devInstId, and fill to page
    if(localStorage.getItem('xds_inst_id'))
    {
        $("#devInstId").val(localStorage.getItem('xds_inst_id'));
        // $("#esp8266_mac").html(localStorage.getItem('xds_inst_id'))
        $("#esp8266_mac").html(localStorage.getItem('xds_inst_id').split(":").slice(3).join("") + ' | ' + localStorage.getItem('xds_inst_name'));
    }
    else
    {
        $("#devInstId").val('');
    }
    
    $("#devCtrl").click(function () {
        // let topic = 'dev/F4:CF:A2:D2:00:18/ctrl';
        // let payload = '1';
        // mqtt.send(topic, payload);
        var xds_inst_id = localStorage.getItem('xds_inst_id');

        // alert("xds_inst_id: " + xds_inst_id);

        if(confirm("确定?"))
        {
            if (isConnected) 
            {
                sendMessage("dev/"+xds_inst_id+"/ctrl", "1");
            }   
            else
            {
                alert("连接已断开，无法发送消息！");
            }
        }

    });
    $("#devReboot").click(function () {
        var xds_inst_id = localStorage.getItem('xds_inst_id');

        if(confirm("重启仪表，确定?"))
        {
            if (isConnected) 
                {
                    sendMessage("dev/"+xds_inst_id+"/ctrl", "0");
                }   
                else
                {
                    alert("连接已断开，无法发送消息！");
                }

        }

    });


    $("#devRebootESP8266").click(function () {

        if(confirm("重启ESP8266，确定?"))
        {
            var xds_inst_id = localStorage.getItem('xds_inst_id');
            if (isConnected) 
                {
                    sendMessage("dev/"+xds_inst_id+"/ctrl", "2");
                }   
                else
                {
                    alert("连接已断开，无法发送消息！");
                }
        }

    });
  

    // get/set params=================================================

    $("#getParams").click(function () {
        var xds_inst_id = localStorage.getItem('xds_inst_id');

        let command = "5503F900AA";  /// 获取各通道的控制设置数据 00为第一通道
        console.log("获取各通道的控制设置数据 ...");
        console.log("command: " + command);
        alert("获取控制数据指令已下发");

        if (isConnected) {
            sendMessage("dev/"+xds_inst_id+"/ctrl", command);
        } else {
            alert("连接已断开，无法发送消息！");
        }
    });


    $("#setParams").click(function () {
        var xds_inst_id = localStorage.getItem('xds_inst_id');

        let target_temp = $("#target_temp").val();
        let target_humi = $("#target_humi").val();
        let ctl_temp = $("#ctl_temp").val();
        let ctl_humi = $("#ctl_humi").val();
        let ctl_temp_time = $("#ctl_temp_time").val();
        let ctl_humi_time = $("#ctl_humi_time").val();
        let heat_time = $("#heat_time").val();
        let humi_time = $("#humi_time").val();
        let frost_time = $("#frost_time").val();
        let stop_time = $("#stop_time").val();

        if (!target_temp || !target_humi || !ctl_temp || !ctl_humi || !ctl_temp_time || !ctl_humi_time || !heat_time || !humi_time || !frost_time || !stop_time) 
        {
            alert("请先将参数填写完整再设置。");
            return;
        }

        // console.log("target_temp: " + target_temp);
        // console.log("target_humi: " + target_humi);
        alert("控制设置数据已下发");

        let command = "551BDA00" +
            parseInt(target_temp*10).toString(16).padStart(4, '0') +
            parseInt(target_humi*10).toString(16).padStart(4, '0') +
            parseInt(ctl_temp*10).toString(16).padStart(4, '0') +
            parseInt(ctl_humi*10).toString(16).padStart(4, '0') +
            parseInt(ctl_temp_time).toString(16).padStart(4, '0') +
            parseInt(ctl_humi_time).toString(16).padStart(4, '0') +
            parseInt(heat_time).toString(16).padStart(4, '0') +
            parseInt(humi_time).toString(16).padStart(4, '0') +
            parseInt(frost_time).toString(16).padStart(4, '0') +
            parseInt(stop_time).toString(16).padStart(4, '0') +
            "00000000" +
            "AA";
        console.log("设置通道的控制设置数据 ...");
        console.log("command: " + command);

        if (isConnected) {
            sendMessage("dev/"+xds_inst_id+"/ctrl", command);
        } else {
            alert("连接已断开，无法发送消息！");
        }
        
    
    });
    //===============================================================


    $("#sendCommandButton").click(function () {
        var xds_inst_id = localStorage.getItem('xds_inst_id');
        let command = $("#commandInput").val().trim();
        //对command进行处理，去掉头尾和中间的空格， 并转换为大写
        command = command.replace(/\s+/g, '').toUpperCase();


        //command 字符串 '55'开头， ’AA'结尾，长度为大于4的偶数
        if (command.length < 4 || command.length % 2 != 0) {
            alert("命令格式错误！");
            return;
        }
        if (command.substring(0, 2) != '55' || command.substring(command.length - 2, command.length) != 'AA') {
            alert("命令格式错误！");
            return;
        }

        alert("通信指令已下发: " + command);    

        if (isConnected) {
            //clean textbox firstly
            $('#responseTextarea').val('');
            sendMessage("dev/"+xds_inst_id+"/ctrl", command);
        } else {

            alert("连接已断开，无法发送消息！");
        }
    });



    // Load and display device list from localStorage
    function loadDeviceList() {
        let devices = JSON.parse(localStorage.getItem('device_list') || '[]');
        let table = $('#dev_id_name_list');
        table.empty();
        
        devices.forEach(device => {
            let row = $('<tr>');
            row.append($('<td>').html('<b style="color:blue" class="select-btn" data-id="' + device.id + '">' + device.name +'</b> <br> <small class="select-btn" data-id="' + device.id + '">' + device.id + '</small>'));
            // row.append($('<td>').text(device.name));
            // row.append($('<td>').text(device.id));
            
            let actions = $('<td>');

            actions.append($('<button>')
                .addClass('btn btn-danger btn-sm delete-btn')
                .css({
                    'margin-right': '10px',
                    'background-color': '#dc3545',
                    'border-color': '#dc3545'
                })
                .html("<i class='bi bi-trash-fill'></i> 删")
                .data('id', device.id));

            actions.append($('<button>')
                .addClass('btn btn-danger btn-sm edit-btn')
                .css({
                    'margin-right': '10px',
                   'background-color': '#28a745', 
                    'border-color': '#28a745'
                })
                .html("<i class='bi bi-pencil-fill'></i> 改")
                .data('id', device.id));

            // actions.append($('<button>')
            //     .addClass('btn btn-success btn-sm select-btn')
            //     .css({
            //         'background-color': '#28a745', 
            //         'border-color': '#28a745'
            //     })
            //     .html("<i class='bi bi-check-circle-fill'></i> 选")
            //     .data('id', device.id));
         
            row.append(actions);
            table.append(row);
        });
    }

    // Handle device selection
    $(document).on('click', '.select-btn', function(e) {
        let deviceId = $(this).data('id');
        localStorage.setItem('xds_inst_id', deviceId);
        
        let devices = JSON.parse(localStorage.getItem('device_list') || '[]');
        let device = devices.find(d => d.id === deviceId);
        if (device) {
            localStorage.setItem('xds_inst_name', device.name);
            $("#esp8266_mac").html(device.id.split(":").slice(3).join("") + ' | ' + device.name);
            // Populate form fields with selected device
            $("#devInstId").val(device.id);
            $("#devInstName").val(device.name);
        }
        
        $('#connectModal').modal('hide');

        // clean all dash board info
        $('#humi').html('-');
        $('#temp').html('-');
        $('#stat').html('-');
        $('#tt').html('-');
        $('#td').html('-');
        $('#th').html('-');
        $('#hd').html('-');
        $('#cm').html('-');
        $('#cs').html('-');
        $('#accm_1').html("<i class='bi bi-record-fill red'></i>");
        $('#accm_2').html("<i class='bi bi-record-fill red'></i>");
        $('#accm_3').html("<i class='bi bi-record-fill red'></i>");
        $('#accm_4').html("<i class='bi bi-record-fill red'></i>");
        $('#status').html('<i class="bi bi-reception-0" style="color:white"></i>');
        $('#responseTextarea').val(''); // for debug window
        $('#commandInput').val(''); // for debug window
        $('#target_temp').val('');
        $('#target_humi').val('');
        $('#ctl_temp').val('');
        $('#ctl_humi').val('');
        $('#ctl_temp_time').val('');
        $('#ctl_humi_time').val('');
        $('#heat_time').val('');
        $('#humi_time').val('');
        $('#frost_time').val('');
        $('#stop_time').val('');

        // $('#tempChart').remove();
        // $('#humiChart').remove();
        // $('#tempChartContainer').append('<canvas id="tempChart"></canvas>');
        // $('#humiChartContainer').append('<canvas id="humiChart"></canvas>');
        // drawChart_temp(tempData);
        // drawChart_humi(humiData);

        // only remove tempChart/humiChart points, not remove chart
        tempData = [];
        humiData = [];
        drawChart_temp(tempData);
        drawChart_humi(tempData);
        
    });

    // Handle device deletion
    $(document).on('click', '.delete-btn', function() {
        if(!confirm("确定要删除吗？"))
        {
            return;
        }

        let deviceId = $(this).data('id');
        let devices = JSON.parse(localStorage.getItem('device_list') || '[]');
        
        devices = devices.filter(d => d.id !== deviceId);
        localStorage.setItem('device_list', JSON.stringify(devices));
        
        loadDeviceList();
    });

    $(document).on('click', '.edit-btn', function() {
        let deviceId = $(this).data('id');
        let devices = JSON.parse(localStorage.getItem('device_list') || '[]');
        let device = devices.find(d => d.id === deviceId);
        
        if (device) {
            $("#devInstId").val(device.id);
            $("#devInstName").val(device.name);
            $("#devInstName").focus();
        }
    });

    $("#switchInstDev").click(function () {
        let deviceId = $("#devInstId").val().trim();
        let deviceName = $("#devInstName").val().trim();
        
        if (!deviceId) return;

        // Get existing devices or initialize empty array
        let devices = JSON.parse(localStorage.getItem('device_list') || '[]');
        
        if (typeof devices === 'string') {
            devices = JSON.parse(devices);
        }

        // Check if device exists
        let existingIndex = devices.findIndex(d => d.id === deviceId);
        
        // Only check limit if adding new device
        if (existingIndex < 0 && devices.length >= 6) {
            alert("设备列表已满，最多只能添加6个设备");
            return;
        }
        
        if (existingIndex >= 0) {
            // Update existing device
            devices[existingIndex].name = deviceName;
        } else {
            // Add new device
            devices.push({
                id: deviceId,
                name: deviceName
            });
        }

        // Save to localStorage
        localStorage.setItem('device_list', JSON.stringify(devices));
        localStorage.setItem('xds_inst_id', deviceId);
        localStorage.setItem('xds_inst_name', deviceName);

        // Update UI
        $("#esp8266_mac").html(deviceId.split(":").slice(3).join("") + ' | ' + deviceName);
        loadDeviceList();
        
        $('#connectModal').modal('hide');
    });

    // Initialize device list on page load
    loadDeviceList();

});

  document.addEventListener('plusready', function() {
      
      checkUpdate();

  });
  
  function checkUpdate() {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', 'https://whxingdasen.cn/xds-esp8266-admin/web/mqttapp-update.json', true);
      xhr.onreadystatechange = function() {
          if (xhr.readyState == 4 && xhr.status == 200) {
              var response = JSON.parse(xhr.responseText);
              var currentVersion = plus.runtime.version;
            //   alert("currentVersion: " + currentVersion);
            //   alert("response.version: " + response.version);
              
              if (response.version > currentVersion) {
                  // 提示用户更新
                  plus.nativeUI.confirm('有新版本可用，是否更新？', function(e) {
                      if (e.index == 0) {
                          // 用户确认更新
      
                          downloadAndUpdate(response.apkUrl);
                      }
                  });
              }
          }
      };
      xhr.send();
  }
  
  function downloadAndUpdate(apkUrl) {

      var dtask = plus.downloader.createDownload(apkUrl, {}, function(d, status) {
          if (status == 200) {
              plus.runtime.install(d.filename, {}, function() {
                  plus.nativeUI.alert('应用更新成功，请重启应用');
              }, function(e) {
                  plus.nativeUI.alert('安装失败: ' + e.message);
              });
          } else {
              plus.nativeUI.alert('下载失败');
          }
      });
     dtask.start();
 }
