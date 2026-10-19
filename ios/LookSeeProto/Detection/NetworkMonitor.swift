//
//  NetworkMonitor.swift
//  LookSeeProto
//
//  Created by Angel Pineda on 7/13/26.
//

import Network
import Foundation
import Combine

@MainActor
class NetworkMonitor: ObservableObject {
    static let shared = NetworkMonitor()
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "NetworkMonitorQueue")
    
    @Published var isConnected: Bool = false
    
    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                let connected = path.status == .satisfied
                let isCellular = path.usesInterfaceType(.cellular)
                let isWiFi = path.usesInterfaceType(.wifi)
                
                if connected != self?.isConnected {
                    let connectionType = isWiFi ? "Wi-Fi" : (isCellular ? "Cellular" : "Other")
                    print("📡 Network status changed: \(connected ? "Connected" : "Disconnected") via \(connectionType)")
                    self?.isConnected = connected
                }
            }
        }
        monitor.start(queue: queue)
    }
}
