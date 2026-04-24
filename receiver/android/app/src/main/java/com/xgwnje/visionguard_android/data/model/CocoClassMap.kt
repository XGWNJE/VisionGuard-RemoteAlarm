package com.xgwnje.visionguard_android.data.model

// ┌─────────────────────────────────────────────────────────┐
// │ CocoClassMap.kt                                        │
// │ COCO 80 类中英文映射（与 Windows CocoClassMap.cs 同步）  │
// └─────────────────────────────────────────────────────────┘

private val enZhMap = mapOf(
    "person" to "人",
    "bicycle" to "自行车",
    "car" to "汽车",
    "motorcycle" to "摩托车",
    "airplane" to "飞机",
    "bus" to "公共汽车",
    "train" to "火车",
    "truck" to "卡车",
    "boat" to "船",
    "traffic light" to "交通灯",
    "fire hydrant" to "消防栓",
    "stop sign" to "停车标志",
    "parking meter" to "停车计时器",
    "bench" to "长椅",
    "bird" to "鸟",
    "cat" to "猫",
    "dog" to "狗",
    "horse" to "马",
    "sheep" to "羊",
    "cow" to "牛",
    "elephant" to "大象",
    "bear" to "熊",
    "zebra" to "斑马",
    "giraffe" to "长颈鹿",
    "backpack" to "背包",
    "umbrella" to "雨伞",
    "handbag" to "手提包",
    "tie" to "领带",
    "suitcase" to "行李箱",
    "frisbee" to "飞盘",
    "skis" to "滑雪板",
    "snowboard" to "单板滑雪",
    "sports ball" to "运动球",
    "kite" to "风筝",
    "baseball bat" to "棒球棒",
    "baseball glove" to "棒球手套",
    "skateboard" to "滑板",
    "surfboard" to "冲浪板",
    "tennis racket" to "网球拍",
    "bottle" to "瓶子",
    "wine glass" to "酒杯",
    "cup" to "杯子",
    "fork" to "叉子",
    "knife" to "刀",
    "spoon" to "勺子",
    "bowl" to "碗",
    "banana" to "香蕉",
    "apple" to "苹果",
    "sandwich" to "三明治",
    "orange" to "橙子",
    "broccoli" to "西兰花",
    "carrot" to "胡萝卜",
    "hot dog" to "热狗",
    "pizza" to "披萨",
    "donut" to "甜甜圈",
    "cake" to "蛋糕",
    "chair" to "椅子",
    "couch" to "沙发",
    "potted plant" to "盆栽",
    "bed" to "床",
    "dining table" to "餐桌",
    "toilet" to "马桶",
    "tv" to "电视",
    "laptop" to "笔记本电脑",
    "mouse" to "鼠标",
    "remote" to "遥控器",
    "keyboard" to "键盘",
    "cell phone" to "手机",
    "microwave" to "微波炉",
    "oven" to "烤箱",
    "toaster" to "烤面包机",
    "sink" to "水槽",
    "refrigerator" to "冰箱",
    "book" to "书",
    "clock" to "时钟",
    "vase" to "花瓶",
    "scissors" to "剪刀",
    "teddy bear" to "泰迪熊",
    "hair drier" to "吹风机",
    "toothbrush" to "牙刷"
)

private val zhEnMap = enZhMap.entries.associate { it.value to it.key }

/** 将 COCO 类英文名翻译为中文，未知类名返回原文 */
fun cocoLabelZh(label: String): String = enZhMap[label] ?: label

/** 中文→英文反查，未知中文返回原文 */
fun cocoLabelEn(zhLabel: String): String = zhEnMap[zhLabel] ?: zhLabel

/** 完整的英中对照列表，供标签选择器使用 */
val cocoEnZhPairs: List<Pair<String, String>> = enZhMap.entries.map { it.key to it.value }

/** 与检测端对齐的 6 类监控目标（person / bicycle / car / motorcycle / bus / truck） */
val targetEnZhPairs: List<Pair<String, String>> = listOf(
    "person" to "人",
    "bicycle" to "自行车",
    "car" to "汽车",
    "motorcycle" to "摩托车",
    "bus" to "公共汽车",
    "truck" to "卡车"
)
