import users from './users/operations.json' with { type: 'json' }
import devices from './devices/operations.json' with { type: 'json' }
import orders from './orders/operations.json' with { type: 'json' }
import process from './process/operations.json' with { type: 'json' }
import images from './images/operations.json' with { type: 'json' }
import evaluation from './evaluation/operations.json' with { type: 'json' }

// 报修项目的接口定义，按功能分组由 doc-groups.js 完成归类。
export default [...users, ...devices, ...orders, ...process, ...images, ...evaluation]
