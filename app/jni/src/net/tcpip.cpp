/*
 *  Abuse - dark 2D side-scrolling platform game
 *  Copyright (c) 1995 Crack dot Com
 *  Copyright (c) 2005-2011 Sam Hocevar <sam@hocevar.net>
 *  Copyright (c) 2024 Andrej Pancik
 *
 *  This software was released into the Public Domain. As with most public
 *  domain software, no warranty is made or implied by Crack dot Com, by
 *  Jonathan Clark, by Sam Hocevar, or Andrej Pancik.
 */
#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#ifdef HAVE_NETWORK

#include "file_utils.h"
#include "tcpip.h"

#ifdef WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "iphlpapi.lib")
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <ifaddrs.h>
#include <unistd.h>
#endif

typedef int socket_t;

// Platform-specific definitions
#ifdef WIN32
#define SOCK_CLOSE closesocket
#define SOCK_IOCTL ioctlsocket
#define INVALID_SOCKET_VALUE INVALID_SOCKET
#define SOCKET_ERROR WSAGetLastError()
#else
#define SOCK_CLOSE close
#define SOCK_IOCTL ioctl
#define INVALID_SOCKET_VALUE -1
#define SOCKET_ERROR errno
#endif

// Global instances
static FILE *log_file = nullptr;
extern int net_start();

#ifdef WIN32
// Windows initialization
class WinSockInitializer
{
public:
  WinSockInitializer()
  {
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0)
    {
      DEBUG_LOG("Failed to initialize WinSock\n");
    }
  }
  ~WinSockInitializer()
  {
    WSACleanup();
  }
};
static WinSockInitializer winsock_init;
#endif

// Logging utilities
static void net_log(const char *st, const void *buf, const long size)
{  
#ifdef TCPIP_DEBUG
  if (!log_file)
  {
    log_file = prefix_fopen(net_start() ? "abuseclient.log" : "abuseserver.log", "wb");
    if (!log_file) {
      DEBUG_LOG("Failed to open log file\n");
      return;
    }
  }

  fprintf(log_file, "%s%ld - ", st, size);

  // Print readable characters
  for (int i = 0; i < size; i++)
  {
    const unsigned char c = *((const unsigned char *)buf + i);
    fprintf(log_file, "%c", isprint(c) ? c : '~');
  }

  fprintf(log_file, " : ");

  // Print hex values
  for (int i = 0; i < size; i++)
  {
    fprintf(log_file, "%02x, ", *((unsigned char *)buf + i));
  }

  fprintf(log_file, "\n");
  fflush(log_file);
#endif
}

// ip_address implementation
ip_address::ip_address(const sockaddr_in *addr)
{
  memcpy(&this->addr, addr, sizeof(this->addr));
}

net_address::protocol ip_address::protocol_type() const
{
  return IP;
}

int ip_address::equal(const net_address *who) const
{
  if (who->protocol_type() != IP)
    return 0;
  return !memcmp(&addr.sin_addr, &((ip_address const *)who)->addr.sin_addr, sizeof(addr.sin_addr));
}

int ip_address::set_port(int port)
{
  addr.sin_port = htons(port);
  return 1;
}

void ip_address::print()
{
  const unsigned char *c = (unsigned char *)&addr.sin_addr.s_addr;
  DEBUG_LOG("%d.%d.%d.%d", c[0], c[1], c[2], c[3]);
}

int ip_address::get_port()
{
  return htons(addr.sin_port);
}

net_address *ip_address::copy()
{
  return new ip_address(&addr);
}

void ip_address::store_string(char *st, const int st_length)
{
  char buf[100];
  const unsigned char *c = (unsigned char *)&addr.sin_addr.s_addr;
  snprintf(buf, sizeof(buf), "%d.%d.%d.%d:%d", c[0], c[1], c[2], c[3], htons(addr.sin_port));
  strncpy(st, buf, st_length);
  st[st_length - 1] = 0;
}

// unix_fd implementation
unix_fd::~unix_fd()
{
  unix_fd::read_unselectable();
  unix_fd::write_unselectable();

  SOCK_CLOSE(fd);
}

int unix_fd::ready_to_write()
{
  timeval tv = {0, 0};
  fd_set write_check;
  FD_ZERO(&write_check);
  FD_SET(fd, &write_check);
  select(FD_SETSIZE, nullptr, &write_check, nullptr, &tv);
  return FD_ISSET(fd, &write_check);
}

int unix_fd::write(void const *buf, int size, net_address *addr)
{
  net_log("unix_fd::write:", static_cast<const char *>(buf), size);
  if (addr)
  {
    DEBUG_LOG("Cannot change address for this socket type\n");
  }
  return ::send(fd, static_cast<const char *>(buf), size, 0);
}

int unix_fd::read(void *buf, int size, net_address **addr)
{
  int tr = ::recv(fd, static_cast<char *>(buf), size, 0);
  net_log("unix_fd::read:", static_cast<const char *>(buf), tr);
  if (addr) *addr = nullptr;
  
  return tr;
}

void unix_fd::broadcastable() const
{
  constexpr int option = 1;
  if (setsockopt(fd, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char *>(&option), sizeof(option)) < 0)
  {
    DEBUG_LOG("Could not set socket option broadcast");
  }
}

// tcp_socket implementation
int tcp_socket::listen(int port)
{
  // Disable Nagle's algorithm
  int flag = 1;
  if (setsockopt(fd, IPPROTO_TCP, TCP_NODELAY,
                 (const char *)&flag, sizeof(flag)) == -1)
  {
    DEBUG_LOG("Could not set TCP_NODELAY");
    return 0;
  }

  // Allow quick restart after shutdown
  if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR,
                 (const char *)&flag, sizeof(flag)) == -1)
  {
    DEBUG_LOG("Could not set SO_REUSEADDR");
    return 0;
  }

  // Keep-alive for detecting dead peers
  if (setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE,
                 (const char *)&flag, sizeof(flag)) == -1)
  {
    DEBUG_LOG("Could not set SO_KEEPALIVE");
    return 0;
  }
  
  sockaddr_in host{};
  host.sin_family = AF_INET;
  host.sin_port = htons(port);
  host.sin_addr.s_addr = htonl(INADDR_ANY);

  if (bind(fd, (sockaddr *)&host, sizeof(host)) == -1)
  {
    DEBUG_LOG("Could not bind socket to port %d\n", port);
    return 0;
  }

  if (::listen(fd, SOMAXCONN) == -1)
  {
    DEBUG_LOG("Could not listen to socket on port %d\n", port);
    return 0;
  }

  listening = true;
  return 1;
}

net_socket *tcp_socket::accept(net_address *&addr)
{
  if (!listening)
  {
    addr = nullptr;
    return nullptr;
  }

  sockaddr_in from{};
  socklen_t addr_len = sizeof(from);
  const socket_t new_fd = ::accept(fd, (sockaddr *)&from, &addr_len);

  if (new_fd != INVALID_SOCKET_VALUE)
  {
    addr = new ip_address(&from);
    return new tcp_socket(new_fd);
  }

  addr = nullptr;
  return nullptr;
}

// udp_socket implementation
int udp_socket::read(void *buf, const int size, net_address **addr)
{
  sockaddr_in temp_addr{};
  socklen_t addr_size = sizeof(sockaddr_in);
  int bytes_received = recvfrom(fd, static_cast<char *>(buf), size, 0, (sockaddr *)&temp_addr, &addr_size);
  if (bytes_received > 0 && addr)
  {
    *addr = new ip_address(&temp_addr);
  }
  return bytes_received;
}

int udp_socket::write(void const *buf, int size, net_address *addr)
{
  if (addr)
  {
    return sendto(fd, static_cast<const char *>(buf), size, 0,
                  (sockaddr *)&((ip_address *)addr)->addr,
                  sizeof(((ip_address *)addr)->addr));
  }

  return ::send(fd, static_cast<const char *>(buf), size, 0);
}

int udp_socket::listen(int port)
{
  sockaddr_in host{};
  host.sin_family = AF_INET;
  host.sin_port = htons(port);
  host.sin_addr.s_addr = htonl(INADDR_ANY);

  if (bind(fd, (sockaddr *)&host, sizeof(host)) == -1)
  {
    DEBUG_LOG("Could not bind socket to port %d\n", port);
    return 0;
  }
  return 1;
}

// tcpip_protocol implementation
tcpip_protocol::tcpip_protocol()
{
  FD_ZERO(&master_set);
  FD_ZERO(&master_write_set);
  FD_ZERO(&read_set);
  FD_ZERO(&exception_set);
  FD_ZERO(&write_set);
}

net_address *tcpip_protocol::get_local_address()
{
#ifdef WIN32
  char host_name[256];
  if (gethostname(host_name, sizeof(host_name)) == 0)
  {
    struct addrinfo hints = {}, *result = nullptr;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    if (getaddrinfo(host_name, nullptr, &hints, &result) == 0)
    {
      for (struct addrinfo *ptr = result; ptr != nullptr; ptr = ptr->ai_next)
      {
        if (ptr->ai_family == AF_INET)
        {
          sockaddr_in *ipv4 = (sockaddr_in *)ptr->ai_addr;
          if (ipv4->sin_addr.s_addr != htonl(INADDR_LOOPBACK))
          {
            auto *addr = new ip_address;
            addr->addr = *ipv4;
            freeaddrinfo(result);
            return addr;
          }
        }
      }
      freeaddrinfo(result);
    }
  }
#else
  struct ifaddrs *ifaddr, *ifa;
  if (getifaddrs(&ifaddr) == -1)
  {
    return nullptr;
  }

  for (ifa = ifaddr; ifa != nullptr; ifa = ifa->ifa_next)
  {
    if (ifa->ifa_addr == nullptr)
    {
      continue;
    }

    if (ifa->ifa_addr->sa_family == AF_INET)
    {
      sockaddr_in *sa_in = (sockaddr_in *)ifa->ifa_addr;
      if (sa_in->sin_addr.s_addr != htonl(INADDR_LOOPBACK))
      {
        auto *addr = new ip_address;
        addr->addr = *sa_in;
        printf("Local IP Address: %s\n", inet_ntoa(addr->addr.sin_addr));
        freeifaddrs(ifaddr);
        return addr;
      }
    }
  }
  freeifaddrs(ifaddr);
#endif

  return nullptr;
}

net_address *tcpip_protocol::get_node_address(char const *&server_name, int def_port, const int force_port)
{
  sockaddr_in host{};
  host.sin_family = AF_INET;

  if (isdigit(server_name[0]))
  {
    unsigned char tmp[4];
    const char *np = server_name;

    for (unsigned char &i : tmp)
    {
      int num = 0;
      while (*np && *np != '.' && *np != ':')
      {
        num = num * 10 + (*np - '0');
        np++;
      }
      i = num;
      if (*np == '.')
        np++;
    }

    if (*np == ':' && !force_port)
    {
      int port;
      if (sscanf(np + 1, "%d", &port) == 1)
      {
        def_port = port;
      }
    }

    host.sin_port = htons(def_port);
    host.sin_addr.s_addr = htonl(INADDR_ANY);
    memcpy(&host.sin_addr, tmp, sizeof(in_addr));

    return new ip_address(&host);
  }

  char name[256];
  const char *src = server_name;
  char *dst = name;
  while (*src && *src != ':' && *src != '/' && dst < name + sizeof(name) - 1)
  {
    *dst++ = *src++;
  }
  *dst = 0;

  if (*src == ':' && !force_port)
  {
    src++;
    int port_num;
    if (sscanf(src, "%d", &port_num) == 1)
    {
      def_port = port_num;
      while (*src && *src != '/')
        src++;
    }
    else
    {
      return nullptr;
    }
  }

  if (*src == '/')
    src++;

#ifdef WIN32
  struct addrinfo hints = {}, *result = nullptr;
  hints.ai_family = AF_INET;
  hints.ai_socktype = SOCK_STREAM;

  if (getaddrinfo(name, nullptr, &hints, &result) != 0)
  {
    DEBUG_LOG("Unable to locate server named '%s'\n", name);
    return nullptr;
  }

  host.sin_port = htons(def_port);
  host.sin_addr = ((struct sockaddr_in *)result->ai_addr)->sin_addr;

  freeaddrinfo(result);
#else
  hostent *hp = gethostbyname(name);
  if (!hp)
  {
    DEBUG_LOG("Unable to locate server named '%s'\n", name);
    return nullptr;
  }

  host.sin_port = htons(def_port);
  host.sin_addr.s_addr = htonl(INADDR_ANY);
  memcpy(&host.sin_addr, hp->h_addr, hp->h_length);
#endif

  server_name = src;
  return new ip_address(&host);
}

net_socket *tcpip_protocol::connect_to_server(net_address *addr, const net_socket::socket_type sock_type)
{
  if (addr->protocol_type() != net_address::IP)
  {
    DEBUG_LOG("Protocol type not supported in the executable\n");
    return nullptr;
  }

  socket_t socket_fd = socket(AF_INET,
                              sock_type == net_socket::SOCKET_SECURE ? SOCK_STREAM : SOCK_DGRAM,
                              0);
  if (socket_fd == INVALID_SOCKET_VALUE)
  {
    DEBUG_LOG("Unable to create socket (too many open files?)\n");
    return nullptr;
  }

  if (connect(socket_fd, (sockaddr *)&((ip_address *)addr)->addr,
              sizeof(((ip_address *)addr)->addr)) == -1)
  {
    DEBUG_LOG("Unable to connect\n");

    SOCK_CLOSE(socket_fd);

    return nullptr;
  }

  return sock_type == net_socket::SOCKET_SECURE ? static_cast<net_socket *>(new tcp_socket(socket_fd)) : static_cast<net_socket *>(new udp_socket(socket_fd));
}

net_socket *tcpip_protocol::create_listen_socket(const int port, const net_socket::socket_type sock_type)
{
  const socket_t socket_fd = socket(AF_INET,
                                    sock_type == net_socket::SOCKET_SECURE ? SOCK_STREAM : SOCK_DGRAM,
                                    0);
  if (socket_fd == INVALID_SOCKET_VALUE)
  {
    DEBUG_LOG("Unable to create socket (too many open files?)\n");
    return nullptr;
  }

  net_socket *s = sock_type == net_socket::SOCKET_SECURE ? static_cast<net_socket *>(new tcp_socket(socket_fd)) : static_cast<net_socket *>(new udp_socket(socket_fd));

  if (!s->listen(port))
  {
    delete s;
    return nullptr;
  }

  return s;
}

void tcpip_protocol::cleanup()
{
  if (notifier)
  {
    end_notify();
  }

  reset_find_list();

  if (responder)
  {
    delete responder;
    delete bcast;
    responder = nullptr;
    bcast = nullptr;
  }
}

net_socket *tcpip_protocol::start_notify(const int port, void *data, const int len)
{
  if (responder)
  {
    delete responder;
    delete bcast;
    responder = nullptr;
    bcast = nullptr;
  }

  const int resp_len = strlen(notify_response);
  notify_len = len + resp_len + 1;
  strcpy(notify_data, notify_response);
  notify_data[resp_len] = '.';
  memcpy(notify_data + resp_len + 1, data, len);

  notifier = create_listen_socket(port, net_socket::SOCKET_FAST);
  if (notifier)
  {
    notifier->read_selectable();
    notifier->write_unselectable();
  }
  else
  {
    DEBUG_LOG("Couldn't start notifier\n");
  }

  return notifier;
}

void tcpip_protocol::end_notify()
{
  delete notifier;
  notifier = nullptr;
  notify_len = 0;
}

int tcpip_protocol::handle_notification() const
{
  if (!notifier)
  {
    return 0;
  }

  if (notifier->ready_to_read())
  {
    char buf[513];
    net_address *tmp;
    const int len = notifier->read(buf, 512, &tmp);
    auto *addr = static_cast<ip_address *>(tmp);

    if (addr && len > 0)
    {
      buf[len] = 0;
      if (strcmp(buf, notify_signature) == 0)
      {
        notifier->write(notify_data, notify_len, addr);
      }
      delete addr;
    }
    return 1;
  }

  if (notifier->error())
  {
    DEBUG_LOG("Error on notification socket!\n");
    return 1;
  }

  return 0;
}

int tcpip_protocol::handle_responder()
{
  if (!responder)
  {
    return 0;
  }

  if (responder->ready_to_read())
  {
    char buf[513];
    net_address *tmp;
    const int len = responder->read(buf, 512, &tmp);
    auto *addr = static_cast<ip_address *>(tmp);

    if (addr && len > 0)
    {
      buf[len] = 0;
      buf[4] = 0; // Limit response signature check
      if (strcmp(buf, notify_response) == 0)
      {
        bool found = false;

        // Check servers list
        for (p_request p = servers.begin(); !found && p != servers.end(); ++p)
        {
          if ((*p)->addr->equal(addr))
          {
            found = true;
          }
        }

        // Check returned list
        for (p_request q = returned.begin(); !found && q != returned.end(); ++q)
        {
          if ((*q)->addr->equal(addr))
          {
            found = true;
          }
        }

        if (!found)
        {
          auto *request = new RequestItem;
          request->addr = addr;
          strncpy(request->name, buf + 5, sizeof(request->name) - 1);
          request->name[sizeof(request->name) - 1] = 0;
          servers.insert(request);
          addr = nullptr; // Prevent deletion
        }
      }

      delete addr; // Delete if not stored in servers list
    }

    return 1;
  }

  if (responder->error())
  {
    DEBUG_LOG("Error on responder socket!\n");
    return 1;
  }

  return 0;
}

int tcpip_protocol::select()
{
  // Copy master sets to working sets since select() modifies them
  memcpy(&read_set, &master_set, sizeof(master_set));
  memcpy(&exception_set, &master_set, sizeof(master_set));
  memcpy(&write_set, &master_write_set, sizeof(master_set));

  timeval nonblocking_timeout = {0, 1000}; // 1ms

  // Non-blocking mode
  int ret = ::select(FD_SETSIZE, &read_set, &write_set, &exception_set, &nonblocking_timeout);

  // Handle any events and update return value
  if (handle_notification()) ret--;
  if (handle_responder()) ret--;  

  return ret;
}

net_address *tcpip_protocol::find_address(const int port, char *name)
{
  end_notify();

  if (!responder)
  {
    responder = create_listen_socket(port, net_socket::SOCKET_FAST);
    if (responder)
    {
      responder->read_selectable();
      responder->write_unselectable();
      bcast = static_cast<ip_address *>(get_local_address());
      if (bcast)
      {
        bcast->set_port(port);
        *((unsigned char *)&bcast->addr.sin_addr + 3) = 0;
      }
    }
  }

  if (responder && bcast)
  {
    for (int i = 0; i < 5; i++)
    {
      bool found = false;

      // Check existing addresses
      for (p_request p = servers.begin(); !found && p != servers.end(); ++p)
      {
        if ((*p)->addr->equal(bcast))
        {
          found = true;
        }
      }

      for (p_request q = returned.begin(); !found && q != returned.end(); ++q)
      {
        if ((*q)->addr->equal(bcast))
        {
          found = true;
        }
      }

      if (!found)
      {
        responder->write(notify_signature, strlen(notify_signature), bcast);
        select();
      }

      *((unsigned char *)&bcast->addr.sin_addr + 3) += 1;
      select();

      if (!servers.empty())
      {
        break;
      }
    }
  }

  if (servers.empty())
  {
    return nullptr;
  }

  // Move first server to returned list and return its address
  servers.move_next(servers.begin_prev(), returned.begin_prev());
  auto *ret = static_cast<ip_address *>((*returned.begin())->addr->copy());
  strncpy(name, (*returned.begin())->name, 256);
  name[255] = 0;

  return ret;
}

void tcpip_protocol::reset_find_list()
{
  for (const auto *p : servers)
  {
    delete p->addr;
    delete p;
  }

  for (const auto *p : returned)
  {
    delete p->addr;
    delete p;
  }

  servers.erase_all();
  returned.erase_all();
}

#endif // HAVE_NETWORK