# COCO 80 类目中英文对照表

YOLOv5nu 模型支持 80 类检测，以下为类目对照表：

| ID | 英文 | 中文 |
|----|------|------|
| 0  | person | 人 |
| 1  | bicycle | 自行车 |
| 2  | car | 汽车 |
| 3  | motorcycle | 摩托车 |
| 4  | airplane | 飞机 |
| 5  | bus | 公共汽车 |
| 6  | train | 火车 |
| 7  | truck | 卡车 |
| 8  | boat | 船 |
| 9  | traffic light | 交通灯 |
| 10 | fire hydrant | 消防栓 |
| 11 | stop sign | 停车标志 |
| 12 | parking meter | 停车计时器 |
| 13 | bench | 长凳 |
| 14 | bird | 鸟 |
| 15 | cat | 猫 |
| 16 | dog | 狗 |
| 17 | horse | 马 |
| 18 | sheep | 羊 |
| 19 | cow | 牛 |
| 20 | elephant | 大象 |
| 21 | bear | 熊 |
| 22 | zebra | 斑马 |
| 23 | giraffe | 长颈鹿 |
| 24 | backpack | 背包 |
| 25 | umbrella | 雨伞 |
| 26 | handbag | 手提包 |
| 27 | tie | 领带 |
| 28 | suitcase | 行李箱 |
| 29 | frisbee | 飞盘 |
| 30 | skis | 滑雪板 |
| 31 | snowboard | 单板滑雪 |
| 32 | sports ball | 运动球 |
| 33 | kite | 风筝 |
| 34 | baseball bat | 棒球棒 |
| 35 | baseball glove | 棒球手套 |
| 36 | skateboard | 滑板 |
| 37 | surfboard | 冲浪板 |
| 38 | tennis racket | 网球拍 |
| 39 | bottle | 瓶子 |
| 40 | wine glass | 酒杯 |
| 41 | cup | 杯子 |
| 42 | fork | 叉子 |
| 43 | knife | 刀 |
| 44 | spoon | 勺子 |
| 45 | bowl | 碗 |
| 46 | banana | 香蕉 |
| 47 | apple | 苹果 |
| 48 | sandwich | 三明治 |
| 49 | orange | 橙子 |
| 50 | broccoli | 西兰花 |
| 51 | carrot | 胡萝卜 |
| 52 | hot dog | 热狗 |
| 53 | pizza | 披萨 |
| 54 | donut | 甜甜圈 |
| 55 | cake | 蛋糕 |
| 56 | chair | 椅子 |
| 57 | couch | 沙发 |
| 58 | potted plant | 盆栽植物 |
| 59 | bed | 床 |
| 60 | dining table | 餐桌 |
| 61 | toilet | 马桶 |
| 62 | tv | 电视 |
| 63 | laptop | 笔记本电脑 |
| 64 | mouse | 鼠标 |
| 65 | remote | 遥控器 |
| 66 | keyboard | 键盘 |
| 67 | cell phone | 手机 |
| 68 | microwave | 微波炉 |
| 69 | oven | 烤箱 |
| 70 | toaster | 烤面包机 |
| 71 | sink | 水槽 |
| 72 | refrigerator | 冰箱 |
| 73 | book | 书 |
| 74 | clock | 时钟 |
| 75 | vase | 花瓶 |
| 76 | scissors | 剪刀 |
| 77 | teddy bear | 泰迪熊 |
| 78 | hair drier | 吹风机 |
| 79 | toothbrush | 牙刷 |

## settings.ini 配置示例

```ini
# 监控对象类型（留空 = 检测全部；填类名 = 只检测指定对象，多个用逗号分隔）
# 类名必须使用英文，可参考上方对照表
WatchedClasses=person,car,bus,truck
```
