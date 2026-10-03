package com.litewidget.app.core.store

/**
 * 内置组件模板。新建组件时写入 widget.json。
 * 结构：
 *   data     —— 组件声明的外部变量（App「变量管理」页自动出表单）
 *   root     —— 4x2 默认设计（兼作老版本 / 无变体时的回退）
 *   variants —— 各尺寸独立设计（4x1 / 2x2 / 2x4 / 4x4），尺寸越大信息越多
 * 这份模板同时是渲染引擎的「样式样张」——渐变卡片、渐变大数字、进度条、条件显隐、变体。
 */
object Templates {

    fun flowCard(): String = """
{
 "version": 1,
 "canvas": {
  "width": 360,
  "height": 180,
  "fit": "auto"
 },
 "vars": {
  "bg1": "#1B1B1F",
  "bg2": "#101014",
  "stroke": "#2A2A30",
  "fg": "#FFFFFF",
  "dim": "#9E9EA6",
  "accent1": "#4CAF50",
  "accent2": "#8BC34A"
 },
 "data": {
  "source": "pddwifi",
  "vars": {
   "baseUrl": {
    "label": "后台地址",
    "type": "url",
    "required": true,
    "hint": "随身 WiFi 管理后台，如 http://pddwifi.gzkpiot.com"
   },
   "devNo": {
    "label": "充值号",
    "type": "string",
    "required": true,
    "secret": true,
    "hint": "SIM 卡对应的充值号"
   }
  }
 },
 "root": {
  "type": "frame",
  "direction": "vertical",
  "justify": "space-between",
  "width": "fill",
  "height": "fill",
  "padding": 16,
  "gap": 7,
  "style": {
   "radius": 20,
   "background": {
    "type": "linear",
    "colors": [
     "#1B1B1F",
     "#101014"
    ],
    "angle": 135
   },
   "border": {
    "color": "#2A2A30",
    "width": 1
   },
   "shadow": {
    "color": "#80000000",
    "blur": 14,
    "x": 0,
    "y": 6
   }
  },
  "children": [
   {
    "type": "frame",
    "direction": "horizontal",
    "children": [
     {
      "type": "text",
      "text": "当前套餐",
      "size": 12,
      "color": "#9E9EA6"
     },
     {
      "type": "spacer",
      "size": "fill"
     },
     {
      "type": "text",
      "bind": {
       "field": "package.expire"
      },
      "size": 11,
      "color": "#6A6A72"
     }
    ],
    "width": "fill"
   },
   {
    "type": "text",
    "bind": {
     "field": "package.name"
    },
    "size": 15,
    "weight": 600,
    "color": "#FFFFFF",
    "maxLines": 1
   },
   {
    "type": "frame",
    "direction": "horizontal",
    "children": [
     {
      "type": "text",
      "bind": {
       "field": "flow.used",
       "format": {
        "unit": "auto",
        "decimals": 1,
        "suffix": " 已用"
       }
      },
      "size": 32,
      "weight": 700,
      "letterSpacing": -0.02,
      "gradient": {
       "type": "linear",
       "colors": [
        "#FFFFFF",
        "#B8B8C4"
       ],
       "angle": 90
      }
     },
     {
      "type": "spacer",
      "size": "fill"
     },
     {
      "type": "text",
      "bind": {
       "field": "flow.remain",
       "format": {
        "unit": "auto",
        "decimals": 1,
        "suffix": " 剩余"
       }
      },
      "size": 12,
      "color": "#9E9EA6"
     }
    ],
    "width": "fill",
    "align": "end"
   },
   {
    "type": "progress",
    "shape": "bar",
    "bind": {
     "field": "flow.percent",
     "format": {
      "unit": "%",
      "decimals": 1
     }
    },
    "thickness": 8,
    "roundCap": true,
    "width": "fill",
    "track": {
     "type": "solid",
     "color": "#26262C"
    },
    "bar": {
     "type": "linear",
     "colors": [
      "#4CAF50",
      "#8BC34A"
     ],
     "angle": 0
    }
   },
   {
    "type": "frame",
    "direction": "horizontal",
    "children": [
     {
      "type": "text",
      "bind": {
       "field": "device.battery",
       "format": {
        "suffix": "% 电量"
       }
      },
      "size": 11,
      "color": "#9E9EA6"
     },
     {
      "type": "spacer",
      "size": "fill"
     },
     {
      "type": "text",
      "bind": {
       "field": "device.ssid"
      },
      "size": 11,
      "color": "#6A6A72"
     },
     {
      "type": "spacer",
      "size": 6
     },
     {
      "type": "text",
      "text": "已断网",
      "size": 11,
      "color": "#FF5252",
      "visibleIf": {
       "field": "device.status",
       "op": "eq",
       "value": 0
      }
     }
    ],
    "width": "fill"
   }
  ]
 },
 "variants": {
  "4x1": {
   "root": {
    "type": "frame",
    "direction": "horizontal",
    "children": [
     {
      "type": "frame",
      "direction": "vertical",
      "children": [
       {
        "type": "text",
        "text": "流量",
        "size": 10,
        "color": "#9E9EA6"
       },
       {
        "type": "text",
        "bind": {
         "field": "flow.used",
         "format": {
          "unit": "auto",
          "decimals": 1,
          "suffix": ""
         }
        },
        "size": 25,
        "weight": 700,
        "letterSpacing": -0.02,
        "gradient": {
         "type": "linear",
         "colors": [
          "#FFFFFF",
          "#B8B8C4"
         ],
         "angle": 90
        }
       }
      ],
      "gap": 3
     },
     {
      "type": "frame",
      "direction": "vertical",
      "children": [
       {
        "type": "progress",
        "shape": "bar",
        "bind": {
         "field": "flow.percent",
         "format": {
          "unit": "%",
          "decimals": 1
         }
        },
        "thickness": 7,
        "roundCap": true,
        "width": "fill",
        "track": {
         "type": "solid",
         "color": "#26262C"
        },
        "bar": {
         "type": "linear",
         "colors": [
          "#4CAF50",
          "#8BC34A"
         ],
         "angle": 0
        }
       },
       {
        "type": "frame",
        "direction": "horizontal",
        "children": [
         {
          "type": "text",
          "bind": {
           "field": "flow.percent",
           "format": {
            "unit": "%",
            "decimals": 1,
            "suffix": " 已用"
           }
          },
          "size": 10,
          "color": "#9E9EA6"
         },
         {
          "type": "spacer",
          "size": "fill"
         },
         {
          "type": "text",
          "bind": {
           "field": "flow.remain",
           "format": {
            "unit": "auto",
            "decimals": 0,
            "suffix": " 剩余"
           }
          },
          "size": 10,
          "color": "#6A6A72"
         }
        ],
        "width": "fill"
       }
      ],
      "width": "fill",
      "gap": 5
     },
     {
      "type": "frame",
      "direction": "vertical",
      "children": [
       {
        "type": "text",
        "bind": {
         "field": "device.battery",
         "format": {
          "suffix": "% 电量"
         }
        },
        "size": 11,
        "color": "#FFFFFF"
       },
       {
        "type": "text",
        "bind": {
         "field": "package.expire"
        },
        "size": 9,
        "color": "#6A6A72",
        "maxLines": 1
       }
      ],
      "align": "end",
      "gap": 3
     }
    ],
    "width": "fill",
    "height": "fill",
    "align": "center",
    "padding": 14,
    "gap": 14,
    "style": {
     "radius": 16,
     "background": {
      "type": "linear",
      "colors": [
       "#1B1B1F",
       "#101014"
      ],
      "angle": 135
     },
     "border": {
      "color": "#2A2A30",
      "width": 1
     },
     "shadow": {
      "color": "#80000000",
      "blur": 14,
      "x": 0,
      "y": 6
     }
    }
   }
  },
  "2x2": {
   "root": {
    "type": "frame",
    "direction": "vertical",
    "children": [
     {
      "type": "frame",
      "direction": "horizontal",
      "children": [
       {
        "type": "text",
        "text": "流量",
        "size": 11,
        "color": "#9E9EA6"
       },
       {
        "type": "spacer",
        "size": "fill"
       },
       {
        "type": "text",
        "bind": {
         "field": "device.battery",
         "format": {
          "suffix": "%"
         }
        },
        "size": 11,
        "color": "#FFFFFF"
       }
      ],
      "width": "fill",
      "align": "center"
     },
     {
      "type": "frame",
      "direction": "vertical",
      "children": [
       {
        "type": "text",
        "bind": {
         "field": "flow.used",
         "format": {
          "unit": "auto",
          "decimals": 1,
          "suffix": ""
         }
        },
        "size": 29,
        "weight": 700,
        "letterSpacing": -0.02,
        "gradient": {
         "type": "linear",
         "colors": [
          "#FFFFFF",
          "#B8B8C4"
         ],
         "angle": 90
        }
       },
       {
        "type": "text",
        "bind": {
         "field": "flow.remain",
         "format": {
          "unit": "auto",
          "decimals": 0,
          "suffix": " 剩余"
         }
        },
        "size": 11,
        "color": "#9E9EA6"
       }
      ],
      "width": "fill",
      "gap": 2
     },
     {
      "type": "progress",
      "shape": "bar",
      "bind": {
       "field": "flow.percent",
       "format": {
        "unit": "%",
        "decimals": 1
       }
      },
      "thickness": 7,
      "roundCap": true,
      "width": "fill",
      "track": {
       "type": "solid",
       "color": "#26262C"
      },
      "bar": {
       "type": "linear",
       "colors": [
        "#4CAF50",
        "#8BC34A"
       ],
       "angle": 0
      }
     },
     {
      "type": "frame",
      "direction": "horizontal",
      "children": [
       {
        "type": "text",
        "bind": {
         "field": "flow.percent",
         "format": {
          "unit": "%",
          "decimals": 1,
          "suffix": " 已用"
         }
        },
        "size": 10,
        "color": "#9E9EA6"
       },
       {
        "type": "spacer",
        "size": "fill"
       },
       {
        "type": "text",
        "bind": {
         "field": "device.ssid"
        },
        "size": 10,
        "color": "#6A6A72",
        "maxLines": 1
       }
      ],
      "width": "fill"
     }
    ],
    "width": "fill",
    "height": "fill",
    "justify": "space-between",
    "padding": 13,
    "gap": 6,
    "style": {
     "radius": 18,
     "background": {
      "type": "linear",
      "colors": [
       "#1B1B1F",
       "#101014"
      ],
      "angle": 135
     },
     "border": {
      "color": "#2A2A30",
      "width": 1
     },
     "shadow": {
      "color": "#80000000",
      "blur": 14,
      "x": 0,
      "y": 6
     }
    }
   }
  },
  "2x4": {
   "root": {
    "type": "frame",
    "direction": "vertical",
    "children": [
     {
      "type": "frame",
      "direction": "horizontal",
      "children": [
       {
        "type": "text",
        "text": "当前套餐",
        "size": 11,
        "color": "#9E9EA6"
       },
       {
        "type": "spacer",
        "size": "fill"
       },
       {
        "type": "text",
        "bind": {
         "field": "package.expire"
        },
        "size": 10,
        "color": "#6A6A72"
       }
      ],
      "width": "fill"
     },
     {
      "type": "text",
      "bind": {
       "field": "package.name"
      },
      "size": 15,
      "weight": 600,
      "color": "#FFFFFF",
      "maxLines": 1
     },
     {
      "type": "frame",
      "direction": "vertical",
      "children": [
       {
        "type": "text",
        "bind": {
         "field": "flow.used",
         "format": {
          "unit": "auto",
          "decimals": 1,
          "suffix": " 已用"
         }
        },
        "size": 33,
        "weight": 700,
        "letterSpacing": -0.02,
        "gradient": {
         "type": "linear",
         "colors": [
          "#FFFFFF",
          "#B8B8C4"
         ],
         "angle": 90
        }
       },
       {
        "type": "text",
        "bind": {
         "field": "flow.remain",
         "format": {
          "unit": "auto",
          "decimals": 1,
          "suffix": " 剩余"
         }
        },
        "size": 12,
        "color": "#9E9EA6"
       }
      ],
      "width": "fill",
      "gap": 3
     },
     {
      "type": "progress",
      "shape": "bar",
      "bind": {
       "field": "flow.percent",
       "format": {
        "unit": "%",
        "decimals": 1
       }
      },
      "thickness": 8,
      "roundCap": true,
      "width": "fill",
      "track": {
       "type": "solid",
       "color": "#26262C"
      },
      "bar": {
       "type": "linear",
       "colors": [
        "#4CAF50",
        "#8BC34A"
       ],
       "angle": 0
      }
     },
     {
      "type": "text",
      "bind": {
       "field": "flow.percent",
       "format": {
        "unit": "%",
        "decimals": 1,
        "suffix": " 已用"
       }
      },
      "size": 11,
      "color": "#9E9EA6"
     },
     {
      "type": "frame",
      "direction": "vertical",
      "children": [
       {
        "type": "frame",
        "direction": "horizontal",
        "children": [
         {
          "type": "text",
          "text": "电量",
          "size": 11,
          "color": "#6A6A72"
         },
         {
          "type": "spacer",
          "size": "fill"
         },
         {
          "type": "text",
          "bind": {
           "field": "device.battery",
           "format": {
            "suffix": "%"
           }
          }
         }
        ],
        "width": "fill"
       },
       {
        "type": "frame",
        "direction": "horizontal",
        "children": [
         {
          "type": "text",
          "text": "余额",
          "size": 11,
          "color": "#6A6A72"
         },
         {
          "type": "spacer",
          "size": "fill"
         },
         {
          "type": "text",
          "bind": {
           "field": "account.balance",
           "format": {
            "prefix": "¥"
           }
          }
         }
        ],
        "width": "fill"
       },
       {
        "type": "frame",
        "direction": "horizontal",
        "children": [
         {
          "type": "text",
          "text": "热点",
          "size": 11,
          "color": "#6A6A72"
         },
         {
          "type": "spacer",
          "size": "fill"
         },
         {
          "type": "text",
          "bind": {
           "field": "device.ssid"
          }
         }
        ],
        "width": "fill"
       }
      ],
      "width": "fill",
      "gap": 7
     },
     {
      "type": "frame",
      "direction": "horizontal",
      "children": [
       {
        "type": "text",
        "text": "已断网",
        "size": 11,
        "color": "#FF5252",
        "visibleIf": {
         "field": "device.status",
         "op": "eq",
         "value": 0
        }
       }
      ],
      "width": "fill"
     }
    ],
    "width": "fill",
    "height": "fill",
    "justify": "space-between",
    "padding": 14,
    "gap": 8,
    "style": {
     "radius": 22,
     "background": {
      "type": "linear",
      "colors": [
       "#1B1B1F",
       "#101014"
      ],
      "angle": 135
     },
     "border": {
      "color": "#2A2A30",
      "width": 1
     },
     "shadow": {
      "color": "#80000000",
      "blur": 14,
      "x": 0,
      "y": 6
     }
    }
   }
  },
  "4x4": {
   "root": {
    "type": "frame",
    "direction": "vertical",
    "children": [
     {
      "type": "frame",
      "direction": "horizontal",
      "children": [
       {
        "type": "text",
        "text": "当前套餐",
        "size": 12,
        "color": "#9E9EA6"
       },
       {
        "type": "spacer",
        "size": "fill"
       },
       {
        "type": "text",
        "bind": {
         "field": "package.expire"
        },
        "size": 11,
        "color": "#6A6A72"
       }
      ],
      "width": "fill",
      "align": "center"
     },
     {
      "type": "text",
      "bind": {
       "field": "package.name"
      },
      "size": 17,
      "weight": 600,
      "color": "#FFFFFF",
      "maxLines": 1
     },
     {
      "type": "frame",
      "direction": "vertical",
      "children": [
       {
        "type": "text",
        "bind": {
         "field": "flow.used",
         "format": {
          "unit": "auto",
          "decimals": 1,
          "suffix": " 已用"
         }
        },
        "size": 44,
        "weight": 700,
        "letterSpacing": -0.02,
        "gradient": {
         "type": "linear",
         "colors": [
          "#FFFFFF",
          "#B8B8C4"
         ],
         "angle": 90
        }
       },
       {
        "type": "frame",
        "direction": "horizontal",
        "children": [
         {
          "type": "text",
          "bind": {
           "field": "flow.remain",
           "format": {
            "unit": "auto",
            "decimals": 1,
            "suffix": " 剩余"
           }
          },
          "size": 12,
          "color": "#9E9EA6"
         },
         {
          "type": "spacer",
          "size": "fill"
         },
         {
          "type": "text",
          "bind": {
           "field": "flow.percent",
           "format": {
            "unit": "%",
            "decimals": 1,
            "suffix": " 已用"
           }
          },
          "size": 12,
          "color": "#9E9EA6"
         }
        ],
        "width": "fill"
       }
      ],
      "width": "fill",
      "gap": 4
     },
     {
      "type": "progress",
      "shape": "bar",
      "bind": {
       "field": "flow.percent",
       "format": {
        "unit": "%",
        "decimals": 1
       }
      },
      "thickness": 10,
      "roundCap": true,
      "width": "fill",
      "track": {
       "type": "solid",
       "color": "#26262C"
      },
      "bar": {
       "type": "linear",
       "colors": [
        "#4CAF50",
        "#8BC34A"
       ],
       "angle": 0
      }
     },
     {
      "type": "frame",
      "direction": "horizontal",
      "children": [
       {
        "type": "frame",
        "direction": "vertical",
        "children": [
         {
          "type": "frame",
          "direction": "horizontal",
          "children": [
           {
            "type": "text",
            "text": "电量",
            "size": 11,
            "color": "#6A6A72"
           },
           {
            "type": "spacer",
            "size": "fill"
           },
           {
            "type": "text",
            "bind": {
             "field": "device.battery",
             "format": {
              "suffix": "%"
             }
            }
           }
          ],
          "width": "fill"
         },
         {
          "type": "frame",
          "direction": "horizontal",
          "children": [
           {
            "type": "text",
            "text": "余额",
            "size": 11,
            "color": "#6A6A72"
           },
           {
            "type": "spacer",
            "size": "fill"
           },
           {
            "type": "text",
            "bind": {
             "field": "account.balance",
             "format": {
              "prefix": "¥"
             }
            }
           }
          ],
          "width": "fill"
         },
         {
          "type": "frame",
          "direction": "horizontal",
          "children": [
           {
            "type": "text",
            "text": "运行",
            "size": 11,
            "color": "#6A6A72"
           },
           {
            "type": "spacer",
            "size": "fill"
           },
           {
            "type": "text",
            "bind": {
             "field": "device.running"
            }
           }
          ],
          "width": "fill"
         }
        ],
        "width": "fill",
        "gap": 9
       },
       {
        "type": "frame",
        "direction": "vertical",
        "children": [
         {
          "type": "frame",
          "direction": "horizontal",
          "children": [
           {
            "type": "text",
            "text": "热点",
            "size": 11,
            "color": "#6A6A72"
           },
           {
            "type": "spacer",
            "size": "fill"
           },
           {
            "type": "text",
            "bind": {
             "field": "device.ssid"
            }
           }
          ],
          "width": "fill"
         },
         {
          "type": "frame",
          "direction": "horizontal",
          "children": [
           {
            "type": "text",
            "text": "SIM",
            "size": 11,
            "color": "#6A6A72"
           },
           {
            "type": "spacer",
            "size": "fill"
           },
           {
            "type": "text",
            "bind": {
             "field": "device.sim"
            }
           }
          ],
          "width": "fill"
         },
         {
          "type": "frame",
          "direction": "horizontal",
          "children": [
           {
            "type": "text",
            "text": "上报",
            "size": 11,
            "color": "#6A6A72"
           },
           {
            "type": "spacer",
            "size": "fill"
           },
           {
            "type": "text",
            "bind": {
             "field": "device.updated"
            }
           }
          ],
          "width": "fill"
         }
        ],
        "width": "fill",
        "gap": 9
       }
      ],
      "width": "fill",
      "gap": 18
     },
     {
      "type": "frame",
      "direction": "horizontal",
      "children": [
       {
        "type": "text",
        "text": "Lite Widget",
        "size": 10,
        "color": "#4A4A52"
       },
       {
        "type": "spacer",
        "size": "fill"
       },
       {
        "type": "text",
        "text": "在线",
        "size": 10,
        "color": "#4CAF50",
        "visibleIf": {
         "field": "device.status",
         "op": "eq",
         "value": 1
        }
       },
       {
        "type": "text",
        "text": "已断网",
        "size": 10,
        "color": "#FF5252",
        "visibleIf": {
         "field": "device.status",
         "op": "eq",
         "value": 0
        }
       }
      ],
      "width": "fill",
      "align": "center"
     }
    ],
    "width": "fill",
    "height": "fill",
    "justify": "space-between",
    "padding": 18,
    "gap": 10,
    "style": {
     "radius": 24,
     "background": {
      "type": "linear",
      "colors": [
       "#1B1B1F",
       "#101014"
      ],
      "angle": 135
     },
     "border": {
      "color": "#2A2A30",
      "width": 1
     },
     "shadow": {
      "color": "#80000000",
      "blur": 14,
      "x": 0,
      "y": 6
     }
    }
   }
  }
 }
}
""".trimIndent()

    /** 内置模板：液态玻璃（亮色磨砂玻璃，App 自带默认组件） */
    fun lightGlass(): String = """
{
 "version": 1,
 "canvas": {
  "width": 360,
  "height": 180,
  "fit": "auto"
 },
 "data": {
  "source": "pddwifi",
  "vars": {
   "baseUrl": {
    "label": "后台地址",
    "type": "url",
    "required": true,
    "hint": "随身 WiFi 管理后台，如 http://pddwifi.gzkpiot.com"
   },
   "devNo": {
    "label": "充值号",
    "type": "string",
    "required": true,
    "secret": true,
    "hint": "SIM 卡对应的充值号"
   }
  }
 },
 "root": {
  "type": "frame",
  "direction": "vertical",
  "justify": "space-between",
  "align": "start",
  "padding": [
   18,
   22,
   16,
   22
  ],
  "width": "fill",
  "height": "fill",
  "style": {
   "radius": 32,
   "background": {
    "type": "frost",
    "blur": 20,
    "opacity": 0.95,
    "tint": "#C7FFFFFF"
   },
   "rim": {
    "top": "#FFFFFF",
    "bottom": "#B3FFFFFF",
    "sides": "#80FFFFFF",
    "width": 1.2
   },
   "innerGlow": {
    "color": "#FFFFFF",
    "width": 44,
    "top": true,
    "bottom": true
   }
  },
  "children": [
   {
    "type": "frame",
    "direction": "horizontal",
    "justify": "space-between",
    "align": "center",
    "width": "fill",
    "children": [
     {
      "type": "frame",
      "direction": "horizontal",
      "align": "center",
      "justify": "center",
      "padding": [
       5,
       11,
       5,
       11
      ],
      "style": {
       "background": {
        "type": "solid",
        "color": "#140D141C"
       },
       "radius": 99
      },
      "children": [
       {
        "type": "text",
        "size": 11,
        "weight": 650,
        "color": "#0D141C",
        "lineHeight": 1.25,
        "text": "随身 WiFi",
        "align": "center",
        "maxLines": 1
       }
      ]
     },
     {
      "type": "frame",
      "direction": "horizontal",
      "align": "center",
      "justify": "center",
      "padding": [
       5,
       11,
       5,
       11
      ],
      "style": {
       "background": {
        "type": "solid",
        "color": "#140D141C"
       },
       "radius": 99
      },
      "children": [
       {
        "type": "text",
        "size": 11,
        "weight": 600,
        "color": "#B30D141C",
        "lineHeight": 1.25,
        "bind": {
         "field": "device.ssid"
        },
        "align": "center",
        "maxLines": 1
       }
      ]
     }
    ]
   },
   {
    "type": "frame",
    "direction": "horizontal",
    "justify": "space-between",
    "align": "end",
    "width": "fill",
    "children": [
     {
      "type": "text",
      "size": 40,
      "weight": 750,
      "color": "#0D141C",
      "lineHeight": 1.05,
      "bind": {
       "field": "flow.used",
       "format": {
        "unit": "auto",
        "decimals": 1
       }
      }
     },
     {
      "type": "frame",
      "direction": "vertical",
      "align": "end",
      "gap": 3,
      "children": [
       {
        "type": "text",
        "size": 10,
        "weight": 500,
        "color": "#730D141C",
        "lineHeight": 1.25,
        "text": "已用占比"
       },
       {
        "type": "text",
        "size": 14,
        "weight": 650,
        "color": "#0D141C",
        "lineHeight": 1.25,
        "bind": {
         "field": "flow.percent",
         "format": {
          "decimals": 2,
          "suffix": "%"
         }
        }
       }
      ]
     }
    ]
   },
   {
    "type": "progress",
    "bind": {
     "field": "flow.percent"
    },
    "min": 0,
    "max": 100,
    "thickness": 7,
    "roundCap": true,
    "width": "fill",
    "track": {
     "type": "solid",
     "color": "#1F0D141C"
    },
    "bar": {
     "type": "linear",
     "colors": [
      "#45A7FA",
      "#0A84FF",
      "#0057E7"
     ],
     "angle": 0
    }
   },
   {
    "type": "frame",
    "direction": "horizontal",
    "justify": "space-between",
    "width": "fill",
    "children": [
     {
      "type": "frame",
      "direction": "vertical",
      "gap": 2,
      "width": "fill",
      "align": "center",
      "padding": [
       7,
       10,
       7,
       10
      ],
      "style": {
       "background": {
        "type": "solid",
        "color": "#140D141C"
       },
       "radius": 16
      },
      "children": [
       {
        "type": "text",
        "size": 14,
        "weight": 650,
        "color": "#0D141C",
        "lineHeight": 1.2,
        "bind": {
         "field": "device.battery",
         "format": {
          "suffix": "%",
          "decimals": 0
         }
        },
        "align": "center",
        "maxLines": 1
       },
       {
        "type": "text",
        "size": 9,
        "weight": 500,
        "color": "#730D141C",
        "lineHeight": 1.2,
        "text": "电量",
        "align": "center",
        "maxLines": 1
       }
      ]
     },
     {
      "type": "frame",
      "direction": "vertical",
      "gap": 2,
      "width": "fill",
      "align": "center",
      "padding": [
       7,
       10,
       7,
       10
      ],
      "style": {
       "background": {
        "type": "solid",
        "color": "#140D141C"
       },
       "radius": 16
      },
      "children": [
       {
        "type": "text",
        "size": 14,
        "weight": 650,
        "color": "#0D141C",
        "lineHeight": 1.2,
        "bind": {
         "field": "device.running"
        },
        "align": "center",
        "maxLines": 1
       },
       {
        "type": "text",
        "size": 9,
        "weight": 500,
        "color": "#730D141C",
        "lineHeight": 1.2,
        "text": "在线时长",
        "align": "center",
        "maxLines": 1
       }
      ]
     },
     {
      "type": "frame",
      "direction": "vertical",
      "gap": 2,
      "width": "fill",
      "align": "center",
      "padding": [
       7,
       10,
       7,
       10
      ],
      "style": {
       "background": {
        "type": "solid",
        "color": "#140D141C"
       },
       "radius": 16
      },
      "children": [
       {
        "type": "text",
        "size": 14,
        "weight": 650,
        "color": "#0D141C",
        "lineHeight": 1.2,
        "bind": {
         "field": "package.expire",
         "format": {
          "date": "MM-DD"
         }
        },
        "align": "center",
        "maxLines": 1
       },
       {
        "type": "text",
        "size": 9,
        "weight": 500,
        "color": "#730D141C",
        "lineHeight": 1.2,
        "text": "到期",
        "align": "center",
        "maxLines": 1
       }
      ]
     }
    ]
   }
  ]
 },
 "variants": {
  "4x1": {
   "root": {
    "type": "frame",
    "direction": "horizontal",
    "align": "center",
    "gap": 9,
    "padding": [
     12,
     16,
     12,
     16
    ],
    "width": "fill",
    "height": "fill",
    "children": [
     {
      "type": "frame",
      "direction": "vertical",
      "gap": 1,
      "children": [
       {
        "type": "text",
        "size": 9,
        "weight": 600,
        "color": "#730D141C",
        "lineHeight": 1.2,
        "maxLines": 1,
        "text": "已用流量"
       },
       {
        "type": "text",
        "size": 22,
        "weight": 800,
        "color": "#0D141C",
        "lineHeight": 1.05,
        "maxLines": 1,
        "bind": {
         "field": "flow.used",
         "format": {
          "unit": "auto",
          "decimals": 1
         }
        }
       }
      ]
     },
     {
      "type": "frame",
      "direction": "vertical",
      "gap": 5,
      "width": "fill",
      "children": [
       {
        "type": "progress",
        "bind": {
         "field": "flow.percent"
        },
        "min": 0,
        "max": 100,
        "thickness": 8,
        "roundCap": true,
        "width": "fill",
        "track": {
         "type": "solid",
         "color": "#1F0D141C"
        },
        "bar": {
         "type": "linear",
         "colors": [
          "#45A7FA",
          "#0A84FF",
          "#0057E7"
         ],
         "angle": 0
        }
       },
       {
        "type": "frame",
        "direction": "horizontal",
        "justify": "space-between",
        "width": "fill",
        "children": [
         {
          "type": "frame",
          "direction": "horizontal",
          "gap": 3,
          "children": [
           {
            "type": "text",
            "size": 9,
            "weight": 500,
            "color": "#730D141C",
            "lineHeight": 1.25,
            "maxLines": 1,
            "text": "已用"
           },
           {
            "type": "text",
            "size": 9,
            "weight": 700,
            "color": "#B30D141C",
            "lineHeight": 1.25,
            "maxLines": 1,
            "bind": {
             "field": "flow.percent",
             "format": {
              "decimals": 2,
              "suffix": "%"
             }
            }
           }
          ]
         },
         {
          "type": "frame",
          "direction": "horizontal",
          "gap": 3,
          "children": [
           {
            "type": "text",
            "size": 9,
            "weight": 500,
            "color": "#730D141C",
            "lineHeight": 1.25,
            "maxLines": 1,
            "text": "剩余"
           },
           {
            "type": "text",
            "size": 9,
            "weight": 700,
            "color": "#B30D141C",
            "lineHeight": 1.25,
            "maxLines": 1,
            "bind": {
             "field": "flow.remain",
             "format": {
              "unit": "auto",
              "decimals": 1
             }
            }
           }
          ]
         }
        ]
       }
      ]
     },
     {
      "type": "frame",
      "width": 1,
      "height": 40,
      "style": {
       "background": {
        "type": "solid",
        "color": "#1F0D141C"
       }
      },
      "children": []
     },
     {
      "type": "frame",
      "direction": "vertical",
      "align": "end",
      "gap": 3,
      "children": [
       {
        "type": "frame",
        "direction": "horizontal",
        "align": "center",
        "gap": 3,
        "children": [
         {
          "type": "text",
          "size": 9,
          "weight": 500,
          "color": "#730D141C",
          "lineHeight": 1.25,
          "maxLines": 1,
          "text": "电量"
         },
         {
          "type": "text",
          "size": 12,
          "weight": 750,
          "color": "#0D141C",
          "lineHeight": 1.25,
          "maxLines": 1,
          "bind": {
           "field": "device.battery",
           "format": {
            "suffix": "%",
            "decimals": 0
           }
          }
         }
        ]
       },
       {
        "type": "frame",
        "direction": "horizontal",
        "align": "center",
        "gap": 3,
        "children": [
         {
          "type": "text",
          "size": 9,
          "weight": 500,
          "color": "#730D141C",
          "lineHeight": 1.25,
          "maxLines": 1,
          "text": "到期"
         },
         {
          "type": "text",
          "size": 12,
          "weight": 750,
          "color": "#0D141C",
          "lineHeight": 1.25,
          "maxLines": 1,
          "bind": {
           "field": "package.expire",
           "format": {
            "date": "MM-DD"
           }
          }
         }
        ]
       }
      ]
     }
    ],
    "style": {
     "radius": 16,
     "background": {
      "type": "frost",
      "blur": 20,
      "opacity": 0.95,
      "tint": "#C7FFFFFF"
     },
     "rim": {
      "top": "#FFFFFF",
      "bottom": "#B3FFFFFF",
      "sides": "#80FFFFFF",
      "width": 1.2
     },
     "innerGlow": {
      "color": "#FFFFFF",
      "width": 44,
      "top": true,
      "bottom": true
     }
    }
   }
  },
  "2x2": {
   "root": {
    "type": "frame",
    "direction": "vertical",
    "justify": "space-between",
    "align": "center",
    "padding": [
     16
    ],
    "width": "fill",
    "height": "fill",
    "style": {
     "radius": 32,
     "background": {
      "type": "frost",
      "blur": 20,
      "opacity": 0.95,
      "tint": "#C7FFFFFF"
     },
     "rim": {
      "top": "#FFFFFF",
      "bottom": "#B3FFFFFF",
      "sides": "#80FFFFFF",
      "width": 1.2
     },
     "innerGlow": {
      "color": "#FFFFFF",
      "width": 44,
      "top": true,
      "bottom": true
     }
    },
    "children": [
     {
      "type": "frame",
      "direction": "horizontal",
      "align": "center",
      "justify": "center",
      "padding": [
       5,
       11,
       5,
       11
      ],
      "style": {
       "background": {
        "type": "solid",
        "color": "#140D141C"
       },
       "radius": 99
      },
      "children": [
       {
        "type": "text",
        "size": 11,
        "weight": 650,
        "color": "#0D141C",
        "lineHeight": 1.25,
        "text": "随身 WiFi",
        "align": "center",
        "maxLines": 1
       }
      ]
     },
     {
      "type": "text",
      "size": 30,
      "weight": 750,
      "color": "#0D141C",
      "lineHeight": 1.05,
      "bind": {
       "field": "flow.used",
       "format": {
        "unit": "auto",
        "decimals": 1
       }
      },
      "align": "center",
      "maxLines": 1
     },
     {
      "type": "progress",
      "bind": {
       "field": "flow.percent"
      },
      "min": 0,
      "max": 100,
      "thickness": 6,
      "roundCap": true,
      "width": "fill",
      "track": {
       "type": "solid",
       "color": "#1F0D141C"
      },
      "bar": {
       "type": "linear",
       "colors": [
        "#45A7FA",
        "#0A84FF",
        "#0057E7"
       ],
       "angle": 0
      }
     },
     {
      "type": "frame",
      "direction": "horizontal",
      "justify": "space-between",
      "width": "fill",
      "children": [
       {
        "type": "frame",
        "direction": "vertical",
        "gap": 2,
        "width": "fill",
        "align": "center",
        "padding": [
         7,
         10,
         7,
         10
        ],
        "style": {
         "background": {
          "type": "solid",
          "color": "#140D141C"
         },
         "radius": 16
        },
        "children": [
         {
          "type": "text",
          "size": 14,
          "weight": 650,
          "color": "#0D141C",
          "lineHeight": 1.2,
          "bind": {
           "field": "device.battery",
           "format": {
            "suffix": "%",
            "decimals": 0
           }
          },
          "align": "center",
          "maxLines": 1
         },
         {
          "type": "text",
          "size": 9,
          "weight": 500,
          "color": "#730D141C",
          "lineHeight": 1.2,
          "text": "电量",
          "align": "center",
          "maxLines": 1
         }
        ]
       },
       {
        "type": "frame",
        "direction": "vertical",
        "gap": 2,
        "width": "fill",
        "align": "center",
        "padding": [
         7,
         10,
         7,
         10
        ],
        "style": {
         "background": {
          "type": "solid",
          "color": "#140D141C"
         },
         "radius": 16
        },
        "children": [
         {
          "type": "text",
          "size": 14,
          "weight": 650,
          "color": "#0D141C",
          "lineHeight": 1.2,
          "bind": {
           "field": "package.expire",
           "format": {
            "date": "MM-DD"
           }
          },
          "align": "center",
          "maxLines": 1
         },
         {
          "type": "text",
          "size": 9,
          "weight": 500,
          "color": "#730D141C",
          "lineHeight": 1.2,
          "text": "到期",
          "align": "center",
          "maxLines": 1
         }
        ]
       }
      ]
     }
    ]
   }
  },
  "2x4": {
   "root": {
    "type": "frame",
    "direction": "vertical",
    "justify": "space-between",
    "align": "start",
    "padding": [
     20,
     14,
     20,
     14
    ],
    "width": "fill",
    "height": "fill",
    "style": {
     "radius": 32,
     "background": {
      "type": "frost",
      "blur": 20,
      "opacity": 0.95,
      "tint": "#C7FFFFFF"
     },
     "rim": {
      "top": "#FFFFFF",
      "bottom": "#B3FFFFFF",
      "sides": "#80FFFFFF",
      "width": 1.2
     },
     "innerGlow": {
      "color": "#FFFFFF",
      "width": 44,
      "top": true,
      "bottom": true
     }
    },
    "children": [
     {
      "type": "frame",
      "direction": "horizontal",
      "align": "center",
      "justify": "center",
      "padding": [
       5,
       11,
       5,
       11
      ],
      "style": {
       "background": {
        "type": "solid",
        "color": "#140D141C"
       },
       "radius": 99
      },
      "children": [
       {
        "type": "text",
        "size": 11,
        "weight": 650,
        "color": "#0D141C",
        "lineHeight": 1.25,
        "text": "随身 WiFi",
        "align": "center",
        "maxLines": 1
       }
      ]
     },
     {
      "type": "frame",
      "direction": "vertical",
      "gap": 8,
      "width": "fill",
      "children": [
       {
        "type": "text",
        "size": 32,
        "weight": 750,
        "color": "#0D141C",
        "lineHeight": 1.05,
        "bind": {
         "field": "flow.used",
         "format": {
          "unit": "auto",
          "decimals": 1
         }
        }
       },
       {
        "type": "progress",
        "bind": {
         "field": "flow.percent"
        },
        "min": 0,
        "max": 100,
        "thickness": 6,
        "roundCap": true,
        "width": "fill",
        "track": {
         "type": "solid",
         "color": "#1F0D141C"
        },
        "bar": {
         "type": "linear",
         "colors": [
          "#45A7FA",
          "#0A84FF",
          "#0057E7"
         ],
         "angle": 0
        }
       },
       {
        "type": "text",
        "size": 11,
        "weight": 600,
        "color": "#B30D141C",
        "lineHeight": 1.25,
        "bind": {
         "field": "flow.percent",
         "format": {
          "decimals": 2,
          "suffix": "%"
         }
        }
       }
      ]
     },
     {
      "type": "frame",
      "direction": "vertical",
      "gap": 6,
      "width": "fill",
      "children": [
       {
        "type": "frame",
        "direction": "vertical",
        "gap": 2,
        "width": "fill",
        "align": "center",
        "padding": [
         7,
         10,
         7,
         10
        ],
        "style": {
         "background": {
          "type": "solid",
          "color": "#140D141C"
         },
         "radius": 16
        },
        "children": [
         {
          "type": "text",
          "size": 14,
          "weight": 650,
          "color": "#0D141C",
          "lineHeight": 1.2,
          "bind": {
           "field": "device.battery",
           "format": {
            "suffix": "%",
            "decimals": 0
           }
          },
          "align": "center",
          "maxLines": 1
         },
         {
          "type": "text",
          "size": 9,
          "weight": 500,
          "color": "#730D141C",
          "lineHeight": 1.2,
          "text": "电量",
          "align": "center",
          "maxLines": 1
         }
        ]
       },
       {
        "type": "frame",
        "direction": "vertical",
        "gap": 2,
        "width": "fill",
        "align": "center",
        "padding": [
         7,
         10,
         7,
         10
        ],
        "style": {
         "background": {
          "type": "solid",
          "color": "#140D141C"
         },
         "radius": 16
        },
        "children": [
         {
          "type": "text",
          "size": 14,
          "weight": 650,
          "color": "#0D141C",
          "lineHeight": 1.2,
          "bind": {
           "field": "device.running"
          },
          "align": "center",
          "maxLines": 1
         },
         {
          "type": "text",
          "size": 9,
          "weight": 500,
          "color": "#730D141C",
          "lineHeight": 1.2,
          "text": "在线时长",
          "align": "center",
          "maxLines": 1
         }
        ]
       },
       {
        "type": "frame",
        "direction": "vertical",
        "gap": 2,
        "width": "fill",
        "align": "center",
        "padding": [
         7,
         10,
         7,
         10
        ],
        "style": {
         "background": {
          "type": "solid",
          "color": "#140D141C"
         },
         "radius": 16
        },
        "children": [
         {
          "type": "text",
          "size": 14,
          "weight": 650,
          "color": "#0D141C",
          "lineHeight": 1.2,
          "bind": {
           "field": "package.expire",
           "format": {
            "date": "MM-DD"
           }
          },
          "align": "center",
          "maxLines": 1
         },
         {
          "type": "text",
          "size": 9,
          "weight": 500,
          "color": "#730D141C",
          "lineHeight": 1.2,
          "text": "到期",
          "align": "center",
          "maxLines": 1
         }
        ]
       }
      ]
     },
     {
      "type": "frame",
      "direction": "horizontal",
      "align": "center",
      "justify": "center",
      "padding": [
       5,
       11,
       5,
       11
      ],
      "style": {
       "background": {
        "type": "solid",
        "color": "#140D141C"
       },
       "radius": 99
      },
      "children": [
       {
        "type": "text",
        "size": 11,
        "weight": 600,
        "color": "#B30D141C",
        "lineHeight": 1.25,
        "bind": {
         "field": "device.ssid"
        },
        "align": "center",
        "maxLines": 1
       }
      ]
     }
    ]
   }
  },
  "4x4": {
   "root": {
    "type": "frame",
    "direction": "vertical",
    "justify": "space-between",
    "align": "start",
    "padding": [
     22,
     24,
     22,
     24
    ],
    "width": "fill",
    "height": "fill",
    "style": {
     "radius": 32,
     "background": {
      "type": "frost",
      "blur": 20,
      "opacity": 0.95,
      "tint": "#C7FFFFFF"
     },
     "rim": {
      "top": "#FFFFFF",
      "bottom": "#B3FFFFFF",
      "sides": "#80FFFFFF",
      "width": 1.2
     },
     "innerGlow": {
      "color": "#FFFFFF",
      "width": 44,
      "top": true,
      "bottom": true
     }
    },
    "children": [
     {
      "type": "frame",
      "direction": "horizontal",
      "justify": "space-between",
      "align": "center",
      "width": "fill",
      "children": [
       {
        "type": "frame",
        "direction": "horizontal",
        "align": "center",
        "justify": "center",
        "padding": [
         5,
         11,
         5,
         11
        ],
        "style": {
         "background": {
          "type": "solid",
          "color": "#140D141C"
         },
         "radius": 99
        },
        "children": [
         {
          "type": "text",
          "size": 11,
          "weight": 650,
          "color": "#0D141C",
          "lineHeight": 1.25,
          "text": "随身 WiFi",
          "align": "center",
          "maxLines": 1
         }
        ]
       },
       {
        "type": "frame",
        "direction": "horizontal",
        "align": "center",
        "justify": "center",
        "padding": [
         5,
         11,
         5,
         11
        ],
        "style": {
         "background": {
          "type": "solid",
          "color": "#140D141C"
         },
         "radius": 99
        },
        "children": [
         {
          "type": "text",
          "size": 11,
          "weight": 600,
          "color": "#B30D141C",
          "lineHeight": 1.25,
          "bind": {
           "field": "device.ssid"
          },
          "align": "center",
          "maxLines": 1
         }
        ]
       }
      ]
     },
     {
      "type": "frame",
      "direction": "vertical",
      "gap": 14,
      "align": "center",
      "width": "fill",
      "children": [
       {
        "type": "text",
        "size": 54,
        "weight": 750,
        "color": "#0D141C",
        "lineHeight": 1.05,
        "bind": {
         "field": "flow.used",
         "format": {
          "unit": "auto",
          "decimals": 1
         }
        }
       },
       {
        "type": "progress",
        "bind": {
         "field": "flow.percent"
        },
        "min": 0,
        "max": 100,
        "thickness": 8,
        "roundCap": true,
        "width": "fill",
        "track": {
         "type": "solid",
         "color": "#1F0D141C"
        },
        "bar": {
         "type": "linear",
         "colors": [
          "#45A7FA",
          "#0A84FF",
          "#0057E7"
         ],
         "angle": 0
        }
       }
      ]
     },
     {
      "type": "frame",
      "direction": "vertical",
      "gap": 8,
      "width": "fill",
      "children": [
       {
        "type": "frame",
        "direction": "horizontal",
        "justify": "space-between",
        "width": "fill",
        "gap": 8,
        "children": [
         {
          "type": "frame",
          "direction": "vertical",
          "gap": 2,
          "width": "fill",
          "align": "center",
          "padding": [
           7,
           10,
           7,
           10
          ],
          "style": {
           "background": {
            "type": "solid",
            "color": "#140D141C"
           },
           "radius": 16
          },
          "children": [
           {
            "type": "text",
            "size": 14,
            "weight": 650,
            "color": "#0D141C",
            "lineHeight": 1.2,
            "bind": {
             "field": "device.battery",
             "format": {
              "suffix": "%",
              "decimals": 0
             }
            },
            "align": "center",
            "maxLines": 1
           },
           {
            "type": "text",
            "size": 9,
            "weight": 500,
            "color": "#730D141C",
            "lineHeight": 1.2,
            "text": "电量",
            "align": "center",
            "maxLines": 1
           }
          ]
         },
         {
          "type": "frame",
          "direction": "vertical",
          "gap": 2,
          "width": "fill",
          "align": "center",
          "padding": [
           7,
           10,
           7,
           10
          ],
          "style": {
           "background": {
            "type": "solid",
            "color": "#140D141C"
           },
           "radius": 16
          },
          "children": [
           {
            "type": "text",
            "size": 14,
            "weight": 650,
            "color": "#0D141C",
            "lineHeight": 1.2,
            "bind": {
             "field": "package.expire",
             "format": {
              "date": "MM-DD"
             }
            },
            "align": "center",
            "maxLines": 1
           },
           {
            "type": "text",
            "size": 9,
            "weight": 500,
            "color": "#730D141C",
            "lineHeight": 1.2,
            "text": "到期",
            "align": "center",
            "maxLines": 1
           }
          ]
         }
        ]
       },
       {
        "type": "frame",
        "direction": "horizontal",
        "justify": "space-between",
        "width": "fill",
        "gap": 8,
        "children": [
         {
          "type": "frame",
          "direction": "vertical",
          "gap": 2,
          "width": "fill",
          "align": "center",
          "padding": [
           7,
           10,
           7,
           10
          ],
          "style": {
           "background": {
            "type": "solid",
            "color": "#140D141C"
           },
           "radius": 16
          },
          "children": [
           {
            "type": "text",
            "size": 14,
            "weight": 650,
            "color": "#0D141C",
            "lineHeight": 1.2,
            "bind": {
             "field": "device.running"
            },
            "align": "center",
            "maxLines": 1
           },
           {
            "type": "text",
            "size": 9,
            "weight": 500,
            "color": "#730D141C",
            "lineHeight": 1.2,
            "text": "在线时长",
            "align": "center",
            "maxLines": 1
           }
          ]
         },
         {
          "type": "frame",
          "direction": "vertical",
          "gap": 2,
          "width": "fill",
          "align": "center",
          "padding": [
           7,
           10,
           7,
           10
          ],
          "style": {
           "background": {
            "type": "solid",
            "color": "#140D141C"
           },
           "radius": 16
          },
          "children": [
           {
            "type": "text",
            "size": 14,
            "weight": 650,
            "color": "#0D141C",
            "lineHeight": 1.2,
            "bind": {
             "field": "package.spec",
             "format": {
              "unit": "auto",
              "decimals": 0
             }
            },
            "align": "center",
            "maxLines": 1
           },
           {
            "type": "text",
            "size": 9,
            "weight": 500,
            "color": "#730D141C",
            "lineHeight": 1.2,
            "text": "套餐总量",
            "align": "center",
            "maxLines": 1
           }
          ]
         }
        ]
       }
      ]
     },
     {
      "type": "text",
      "size": 11,
      "weight": 500,
      "color": "#730D141C",
      "lineHeight": 1.25,
      "bind": {
       "field": "package.name"
      },
      "align": "center",
      "maxLines": 1
     }
    ]
   }
  }
 }
}
"""

    fun emptyCard(id: String, name: String): String = """
{
  "version": 1,
  "canvas": { "width": 360, "height": 180, "fit": "auto" },
  "root": {
    "type": "frame",
    "width": "fill",
    "height": "fill",
    "padding": 16,
    "style": {
      "radius": 20,
      "background": { "type": "solid", "color": "#17171D" },
      "border": { "color": "#26262E", "width": 1 }
    },
    "children": [
      { "type": "text", "text": "$name", "size": 16, "weight": 600, "color": "#FFFFFF" },
      { "type": "spacer", "size": 8 },
      { "type": "text", "text": "id: $id — 通过 MCP 改 widget.json 来画它", "size": 12, "color": "#8A8A96" }
    ]
  }
}
""".trimIndent()
}
